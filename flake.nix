{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    android-hidden-api = {
      url = "github:anggrayudi/android-hidden-api";
      flake = false;
    };
  };

  outputs = { self, nixpkgs, android-hidden-api }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
        config.android_sdk.accept_license = true;
      };

      androidComposition = pkgs.androidenv.composeAndroidPackages {
        includeNDK = true;
        ndkVersions = [ "25.1.8937393" ];
        platformVersions = [ "33" "36" ];
        buildToolsVersions = [ "34.0.0" ];
        cmakeVersions = [ "3.22.1" ];
        includeSystemImages = false;
        includeSources = false;
        includeEmulator = false;
      };

      hiddenjar = pkgs.stdenvNoCC.mkDerivation (finalAttrs: {
        pname = "hiddenjar";
        version = "1.0.0-unstable-2026-07-13";

        src = android-hidden-api;

        nativeBuildInputs = [ pkgs.makeWrapper ];

        dontConfigure = true;
        dontBuild = true;

        installPhase =
          let
            binPath = pkgs.lib.makeBinPath (with pkgs; [
              jdk17
              android-tools
              unzip
              zip
              curl
            ]);
          in
          ''
            runHook preInstall

            install -Dm755 cli/hiddenjar -t $out/libexec/${finalAttrs.pname}
            install -Dm644 cli/BuildJar.java cli/ClosureVerify.java cli/Stubifier.java -t $out/libexec/${finalAttrs.pname}
            makeWrapper $out/libexec/${finalAttrs.pname}/hiddenjar $out/bin/hiddenjar --prefix PATH : "${binPath}"

            runHook postInstall
          '';
      });
    in
    {
      packages.${system}.hiddenjar = hiddenjar;

      devShell.${system} = pkgs.mkShell {
        shellHook = ''
          export ANDROID_HOME=${androidComposition.androidsdk}/libexec/android-sdk
          export ANDROID_SDK_ROOT="$ANDROID_HOME"
          export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/25.1.8937393"
          export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
        '';

        buildInputs = with pkgs; [
          apksigner
          apktool
          unzip
          zip
          curl
          file
          jdk17
          android-tools
          samloader-rs
          androidComposition.androidsdk
          cmake
          hiddenjar
          baresip
        ];
      };
    };
}
