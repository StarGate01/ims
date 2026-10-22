# Building and installing without a platform key

How to build this tree standalone with Gradle and install it as a privileged
system app on a device whose platform signing key is not available, such as a
LineageOS build. The result is a debug-signed APK running under an ordinary app
UID rather than the system UID.

Tested on `beyond2lte` (Samsung Galaxy S10+, Exynos 9820), LineageOS 23.2,
Android 16 (API 36).

## 1. Prerequisites

| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK platforms | 33 (`compileSdk`) and the API level of the target device |
| Android SDK build-tools | 34.0.0 |
| Android NDK | 25.1.8937393 |
| SDK CMake | 3.22.1 (the SDK-manager package; AGP resolves this exact version) |
| Other | `adb`, `unzip`, `zip`, `curl` |

`hiddenjar` (section 2) downloads and caches its own dex-tools on first run,
hence `curl`; pass it `--dex-tools DIR` to work offline.

**With Nix**, `flake.nix` in the repo root provides all of the above plus
`hiddenjar` itself, and exports `ANDROID_HOME`, `ANDROID_SDK_ROOT` and the NDK
paths:

```sh
nix develop            # from the repo root; or `nix develop /path/to/ims`
```

Every command in this document can then be run inside that shell, or prefixed
individually:

```sh
nix develop --command <command>
```

Without Nix, install the tools above by hand and export the SDK location; the
rest of this document assumes it. Set `ANDROID_SDK_ROOT` to the same path — AGP
fails with "Several environment variables and/or system properties contain
different paths to the SDK" if they diverge. The SDK is only read, so it may
live on a read-only filesystem:

```sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

Clone with submodules — `rnnoise` and `webrtc-aec3` are compiled by CMake:

```sh
git clone --branch beyond2lte --recurse-submodules <repo> ims
# existing clone:
git -C ims checkout beyond2lte
git -C ims submodule update --init --recursive
```

The `beyond2lte` branch is the one these instructions describe: the Gradle
`:overlay` module, the `platformApiJar` bootclasspath handling and `flake.nix`
are not on the default branch.

**Device requirements.** Installation writes to `/system` and `/product`, so the
target device needs:

- `adb root` — an `eng` or `userdebug` build, or another route to a root adbd.
  Verify with `adb root && adb shell id`, which must report `uid=0`.
- writable `/system` and `/product`, for example via `adb disable-verity` /
  `adb remount` or an existing OverlayFS setup. Verify with
  `adb shell mount -o rw,remount /system`.

Building the platform jar in section 2 also needs `adb root`, to read
`/system/framework`.

## 2. Build the platform jar

The public SDK jar does not expose the internal framework APIs this project
uses, so the build needs a jar that does. Build it with the `hiddenjar` CLI from
[`anggrayudi/android-hidden-api`](https://github.com/anggrayudi/android-hidden-api):
it pulls every `$BOOTCLASSPATH` jar from a connected device, converts the DEX,
merges the result onto the stock SDK jar, strips it to signature-only stubs and
verifies that it compiles.

Run from the repository root, so the jar lands beside the sources that use it:

```sh
git clone https://github.com/anggrayudi/android-hidden-api.git /tmp/android-hidden-api
/tmp/android-hidden-api/cli/hiddenjar doctor     # checks adb, javac, jar, unzip, zip
/tmp/android-hidden-api/cli/hiddenjar build --output platform-api.jar
```

In the Nix devshell `hiddenjar` is already on `PATH`, so the clone is
unnecessary:

```sh
nix develop --command hiddenjar build --output platform-api.jar
```

The jar is **not** installed into the SDK. Pass it to Gradle instead, with the
`platformApiJar` property (section 4); `app/build.gradle` then puts it on the
Kotlin compile classpath and prepends it to javac's bootclasspath. The SDK stays
untouched and may be read-only.

`platform-api.jar` is a build artifact, regenerated from the device whenever the
target API level changes, so `.gitignore` excludes it. Paths given to
`platformApiJar` are resolved from the repository root.

Notes:

- Requires a device or emulator image at API >= 34. Older ones ship stripped
  `framework.jar` files with no usable DEX, which the tool detects and reports.
  `userdebug` / `dev-keys` builds carry full DEX.
- `adb root` is required: the tool reads `/system/framework` to enumerate
  `$BOOTCLASSPATH`.
- Rebuild per API level; hidden signatures change between releases.
- The jar affects compilation only. Runtime non-SDK restrictions still apply and
  are handled in section 8.
- Keep `app/libs/android.jar` and `app/libs/ImsMediaFramework.jar` in place.
  With `platformApiJar` set, `app/build.gradle` ignores `libs/*.jar` except for
  `ImsMediaFramework.jar`, so the checked-in compatibility jar is simply unused.

## 3. versionCode

PackageManager does not re-scan a replaced system APK that keeps the same
`versionCode`; the old manifest stays in effect even after a reboot. Increment
`versionCode` in `app/build.gradle` for every build that will be installed.

## 4. Build PhhIms

Everything below runs from the repository root:

```sh
./gradlew :app:assembleDebug -PplatformApiJar=platform-api.jar
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

`platformApiJar` is required. Without it, `app/build.gradle` falls back to
`app/libs/*.jar` and skips the javac bootclasspath entry, and the build fails
with `CapabilityCallbackProxy has protected access in ImsFeature` in
`PhhMmTelFeatureProtected.java`.

`[CXX5304] Observed package id 'ndk;…' in inconsistent location` is harmless.

## 5. Build the overlays

Two RROs, because one RRO targets exactly one package:

```sh
./gradlew :overlay:assembleDebug       # targets com.android.phone
./gradlew :overlay-fwk:assembleDebug   # targets android (framework-res)
```

| Module | Target | Resource | Why |
|---|---|---|---|
| `overlay/` | `com.android.phone` | `config_ims_mmtel_package` = `me.phh.ims` | binds the MMTEL service to this app |
| `overlay-fwk/` | `android` | `config_use_voip_mode_for_ims` = `true` | makes telephony report IMS calls as VoIP audio mode, so Telecom owns `MODE_IN_COMMUNICATION` and its in-call audio route controls work |

Outputs: `overlay/build/outputs/apk/debug/overlay-debug.apk` and
`overlay-fwk/build/outputs/apk/debug/overlay-fwk-debug.apk`, signed with the
same debug keystore as PhhIms itself. Any signing key works — a preinstalled
static RRO in a partition `overlay/` directory is trusted by location, not
signature.

`config_use_voip_mode_for_ims` is a **resource**, not a setting, so there is no
`settings put` equivalent; an RRO is the only durable override. For one-off
experiments on a framework resource, `cmd overlay fabricate` needs no APK but
does **not** survive a reboot.

## 6. Privileged permission allowlist

Devices with `ro.control_privapp_permissions=enforce` deny any
`signature|privileged` permission that is not allowlisted. The tree carries the
allowlist at `permissions/privapp-permissions-me.phh.ims.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="me.phh.ims">
        <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE"/>
        <permission name="android.permission.MODIFY_PHONE_STATE"/>
        <permission name="android.permission.SCHEDULE_EXACT_ALARM"/>
        <permission name="android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS"/>
    </privapp-permissions>
</permissions>
```

The remaining requested permissions are normal-level (automatic) or runtime
(granted in section 8).

## 7. Install

Requires root and a writable `/system` and `/product` (for example an
`adb remount` / OverlayFS-capable build).

```sh
adb root
adb shell mount -o rw,remount /system
adb shell mount -o rw,remount /product

adb shell mkdir -p /system/priv-app/PhhIms
adb push app/build/outputs/apk/debug/app-debug.apk /system/priv-app/PhhIms/PhhIms.apk
adb push overlay/build/outputs/apk/debug/overlay-debug.apk /product/overlay/PhhImsOverlay.apk
adb push overlay-fwk/build/outputs/apk/debug/overlay-fwk-debug.apk /product/overlay/PhhImsFrameworkOverlay.apk
adb push permissions/privapp-permissions-me.phh.ims.xml /system/etc/permissions/

adb shell chmod 755 /system/priv-app/PhhIms
adb shell chmod 644 /system/priv-app/PhhIms/PhhIms.apk /product/overlay/PhhImsOverlay.apk /product/overlay/PhhImsFrameworkOverlay.apk /system/etc/permissions/privapp-permissions-me.phh.ims.xml

adb reboot
```

Priv-app scanning and static RRO registration happen only at boot.

Neither `chmod` is optional. `adb shell` runs with `umask 000`, so `mkdir -p`
leaves the directory `drwxrwxrwx`. `adb push` writes 644 when it *creates* a
file, but not reliably when it overwrites one: a reinstall over files previously
created at 666 was observed to leave them at 666. Set the modes explicitly
rather than relying on the push.

`restorecon` is not required on this path — the SELinux label comes from
`file_contexts` for the destination, and `restorecon -RFv` reports nothing to
relabel.

**If a push reports a read-only filesystem**, adbd is not seeing the remount —
its mount namespace can differ from the one `adb shell mount -o rw,remount`
changed. Use `adb remount`, or stage each file through a writable directory, in
which case the mode and label *do* have to be set afterwards:

```sh
adb push <file> /data/local/tmp/
adb shell cp /data/local/tmp/<file> <dest>
adb shell chmod 644 <dest>          # cp creates 666 under umask 000
adb shell restorecon -F <dest>      # needed if <dest> was written by mv, which
                                    # preserves the source's xattrs
```

If the device declares no `android.hardware.telephony.ims` feature
(`adb shell pm list features | grep telephony.ims`), also install AOSP's
`frameworks/native/data/etc/android.hardware.telephony.ims.xml` into
`/system/etc/permissions/`.

## 8. Post-installation

All four steps are required.

**Grant the runtime permissions.** PhhIms is headless and cannot prompt:

```sh
adb shell pm grant me.phh.ims android.permission.READ_PHONE_STATE
adb shell pm grant me.phh.ims android.permission.RECORD_AUDIO
adb shell pm grant me.phh.ims android.permission.ACCESS_FINE_LOCATION
adb shell pm grant me.phh.ims android.permission.ACCESS_COARSE_LOCATION
```

**Exempt the non-SDK interfaces it uses.** Running outside the platform domain
subjects the app to hidden-API enforcement; without this,
`ImsFeature.initialize()` is denied, `createMmTelFeature` throws and the MMTEL
binder is null:

```sh
adb shell "settings put global hidden_api_blacklist_exemptions \
'Landroid/telephony/,Landroid/os/SystemProperties;->,\
Ljava/net/ServerSocket;->getFileDescriptor\$,Ljava/net/Socket;->getFileDescriptor\$,\
Lsun/nio/ch/DatagramSocketAdaptor;->getFileDescriptor\$'"
```

Quote the whole `settings put` as one argument; `$` and `;` otherwise break
host-shell parsing. The list is applied at process fork, so
`adb shell am force-stop me.phh.ims` is enough to pick up a change.

**This setting is device-wide.** There is no per-package equivalent: zygote
applies the list to every process it forks, so the listed prefixes — notably
`Landroid/telephony/` — stop being enforced for *all* apps, not just PhhIms. It
grants no permission and does not affect SELinux or verified boot, but it is a
development-device tradeoff, not a production configuration. Revert with
`adb shell settings delete global hidden_api_blacklist_exemptions`.

Verify, filtered to this package rather than device-wide — unrelated apps
routinely trip non-SDK checks of their own:

```sh
adb shell logcat -b all -d | grep 'hiddenapi.*denied' | grep -c me.phh.ims   # 0
```

**Enable VoLTE.** Bring-up helpers; proper CarrierConfig values are preferable
where available:

```sh
adb shell setprop persist.dbg.volte_avail_ovr 1
adb shell setprop persist.dbg.wfc_avail_ovr 1
adb shell setprop persist.dbg.allow_ims_off 1
adb shell settings put global enhanced_4g_mode_enabled 1
```

**Reboot.** Telephony reads these at init.

```sh
adb reboot
```

## 9. Verification

PhhIms logs through `Rlog`, i.e. the **radio** buffer. Plain `adb logcat` shows
nothing from it.

| Check | Command | Expected |
|---|---|---|
| Scanned as priv-app | `adb shell dumpsys package me.phh.ims \| grep -E 'codePath\|privateFlags'` | `/system/priv-app/PhhIms`, `PRIVILEGED` |
| Privileged permissions | `adb shell dumpsys package me.phh.ims \| grep granted=` | `granted=true` |
| Overlays enabled | `adb shell cmd overlay list --user 0 \| grep phh` | `[x] me.phh.ims.overlay` and `[x] me.phh.ims.overlay.fwk` |
| VoIP audio mode for IMS | `adb shell cmd overlay lookup android android:bool/config_use_voip_mode_for_ims` | `true` |
| MMTEL provider resolved | `adb shell cmd phone ims get-ims-service -d -f 1` | `me.phh.ims` |
| Feature created | `adb shell logcat -b radio -d \| grep 'PHH ImsService'` | `createMmTelFeatureForSubscription` |
| Registration | `adb shell logcat -b radio -d \| grep 'PHH'` | `REGISTER 200 OK`, `Final MMTEL capabilities=9` |
| Hidden-API clean | `adb shell logcat -b all -d \| grep -c 'hiddenapi.*denied.*phh'` | `0` — scope it to the package: unrelated apps produce denials of their own, so the unscoped count is not `0` on a real device |

Then test calls in both directions and confirm two-way audio.

## 10. Reinstalling

```sh
# bump versionCode in app/build.gradle first
./gradlew :app:assembleDebug -PplatformApiJar=platform-api.jar
adb root && adb shell mount -o rw,remount /system
adb push app/build/outputs/apk/debug/app-debug.apk /system/priv-app/PhhIms/PhhIms.apk
adb shell chmod 644 /system/priv-app/PhhIms/PhhIms.apk
adb reboot
```

If the new APK keeps the same `versionCode` (section 3), PackageManager reuses
its cached parse and the reboot alone will not pick up manifest changes. Clearing
the parse cache forces a re-scan:

```sh
adb shell rm -rf /data/system/package_cache/*
```

That cache is shared by every package, not just this one. It holds nothing but
parse results and is rebuilt at the next boot, so dropping it is safe — but it is
a fallback for a same-`versionCode` install, not a routine step.

`am force-stop me.phh.ims` re-binds the service and picks up a changed
exemption list, but not a replaced APK or changed properties.

Settings and permissions persist across reinstalls and OS updates; files under
`/system` and `/product` may not survive a ROM flash, so re-check section 9
afterwards.
