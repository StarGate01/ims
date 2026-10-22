//SPDX-License-Identifier: GPL-2.0
package me.phh.ims

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.SystemClock
import android.telephony.Rlog
<<<<<<< HEAD
import android.telephony.TelephonyManager
=======
import android.telephony.SubscriptionManager
>>>>>>> c40db2d (ims: groundwork for emergency calling over IMS, off by default)
import android.telephony.ims.ImsService
import android.telephony.ims.feature.ImsFeature
import android.telephony.ims.feature.MmTelFeature
import android.telephony.ims.feature.RcsFeature
import android.telephony.ims.stub.ImsConfigImplBase
import android.telephony.ims.stub.ImsFeatureConfiguration
import android.telephony.ims.stub.ImsRegistrationImplBase

class PhhImsService : ImsService() {
    companion object {
        private const val TAG = "PHH ImsService"
        private const val PERIODIC_REGISTER_EXACT_DELAY_MS = 3_000_000L
        private const val PERIODIC_REGISTER_INEXACT_FALLBACK_DELAY_MS = 1_800_000L

        var instance: PhhImsService? = null
    }

    private val receiver = PhhImsBroadcastReceiver()
    private var receiverRegistered = false
    private val stateLock = Any()

    private val mmTelFeaturesBySlot = mutableMapOf<Int, PhhMmTelFeature>()
    private val configsBySlot = mutableMapOf<Int, PhhImsConfig>()
    private val imsRegistrationsBySlot = mutableMapOf<Int, ImsRegistrationImplBase>()

    override fun onCreate() {
        super.onCreate()
        Rlog.d(TAG, "onCreate")

        val intentFilter = IntentFilter()
        intentFilter.addAction(receiver.ALARM_PERIODIC_REGISTER)

        registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        armPeriodicRegisterAlarm()
    }

    private fun periodicRegisterPendingIntent(): PendingIntent {
        val intent = Intent(receiver.ALARM_PERIODIC_REGISTER)
        return PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun armPeriodicRegisterAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = periodicRegisterPendingIntent()

        // The carrier may grant only a one-hour registration lifetime. An
        // inexact 3000s alarm receives a large delivery window and can fire
        // after that registration and its IPsec association have expired.
        val nowMs = SystemClock.elapsedRealtime()
        var exactAlarmScheduled = false
        if (alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    nowMs + PERIODIC_REGISTER_EXACT_DELAY_MS,
                    pendingIntent,
                )
                exactAlarmScheduled = true
            } catch (e: SecurityException) {
                Rlog.w(TAG, "Exact periodic REGISTER alarm was denied", e)
            }
        }

        val delayMs = if (exactAlarmScheduled) {
            PERIODIC_REGISTER_EXACT_DELAY_MS
        } else {
            // Keep the inexact alarm's delivery window safely below the
            // carrier-granted lifetime if exact alarm access is unavailable.
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                nowMs + PERIODIC_REGISTER_INEXACT_FALLBACK_DELAY_MS,
                pendingIntent,
            )
            PERIODIC_REGISTER_INEXACT_FALLBACK_DELAY_MS
        }

        Rlog.d(TAG, "Alarm set exact=$exactAlarmScheduled delayMs=$delayMs")
    }

    private fun notifyFeatureSubscriptionChanged(
        slotId: Int,
        subscriptionId: Int,
        reason: String,
    ) {
        val feature = synchronized(stateLock) { mmTelFeaturesBySlot[slotId] } ?: return

        Rlog.d(
            TAG,
            "notifyFeatureSubscriptionChanged slotId=$slotId " +
                "subscriptionId=$subscriptionId reason=$reason",
        )
        feature.onSubscriptionChangedFromService(subscriptionId)
    }

    override fun createMmTelFeatureForSubscription(
        slotId: Int,
        subscriptionId: Int,
    ): MmTelFeature {
        Rlog.d(
            TAG,
            "createMmTelFeatureForSubscription slotId=$slotId subscriptionId=$subscriptionId",
        )

        val existingFeature = synchronized(stateLock) { mmTelFeaturesBySlot[slotId] }
        if (existingFeature != null) {
            notifyFeatureSubscriptionChanged(
                slotId,
                subscriptionId,
                "createMmTelFeatureForSubscription existing feature",
            )
            return existingFeature
        }

        return synchronized(stateLock) {
            mmTelFeaturesBySlot.getOrPut(slotId) {
                PhhMmTelFeature(slotId, subscriptionId)
            }
        }
    }

    /**
     * Emergency-only MMTEL feature, created when the slot has no usable subscription.
     *
     * The framework calls this only when no MMTEL feature exists for the slot yet
     * (`ImsService.createEmergencyOnlyMmTelFeature` returns the existing feature's binder
     * otherwise), and registers whatever is returned as `FEATURE_MMTEL`. Returning the same
     * per-slot feature therefore keeps a single instance across the no-SIM to SIM transition;
     * `createMmTelFeatureForSubscription` then notifies it of the real subscription.
     *
     * Without a subscription there are no IMS credentials, so this instance cannot register and
     * will decline emergency calls over IMS — telephony then places them over CS, which is the
     * correct outcome for a device with no usable SIM.
     */
    override fun createEmergencyOnlyMmTelFeature(slotId: Int): MmTelFeature {
        Rlog.d(TAG, "createEmergencyOnlyMmTelFeature slotId=$slotId")

        return synchronized(stateLock) {
            mmTelFeaturesBySlot.getOrPut(slotId) {
                PhhMmTelFeature(slotId, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            }
        }
    }

    override fun createRcsFeatureForSubscription(
        slotId: Int,
        subscriptionId: Int,
    ): RcsFeature? {
        Rlog.d(TAG, "createRcsFeatureForSubscription slotId=$slotId subscriptionId=$subscriptionId")
        return null
    }

    override fun getConfigForSubscription(
        slotId: Int,
        subscriptionId: Int,
    ): ImsConfigImplBase {
        Rlog.d(TAG, "getConfigForSubscription slotId=$slotId subscriptionId=$subscriptionId")

        notifyFeatureSubscriptionChanged(slotId, subscriptionId, "getConfigForSubscription")

        return synchronized(stateLock) {
            configsBySlot.getOrPut(slotId) {
                PhhImsConfig()
            }
        }
    }

    override fun getRegistrationForSubscription(
        slotId: Int,
        subscriptionId: Int,
    ): ImsRegistrationImplBase {
        Rlog.d(TAG, "getRegistrationForSubscription slotId=$slotId subscriptionId=$subscriptionId")

        notifyFeatureSubscriptionChanged(slotId, subscriptionId, "getRegistrationForSubscription")

        return synchronized(stateLock) {
            imsRegistrationsBySlot.getOrPut(slotId) {
                ImsRegistrationImplBase()
            }
        }
    }

    fun getActiveSipHandlers() =
        synchronized(stateLock) {
            mmTelFeaturesBySlot.values.toList()
        }.mapNotNull { it.getSipHandlerOrNull() }

    inner class LocalBinder : Binder() {
        fun getService(): PhhImsService {
            Rlog.d(TAG, "LocalBinder getService")
            return this@PhhImsService
        }
    }

    override fun onDestroy() {
        Rlog.d(TAG, "onDestroy")

        val features = synchronized(stateLock) {
            val snapshot = mmTelFeaturesBySlot.values.toList()
            mmTelFeaturesBySlot.clear()
            configsBySlot.clear()
            imsRegistrationsBySlot.clear()
            snapshot
        }
        features.forEach { feature ->
            try {
                feature.onFeatureRemoved()
            } catch (t: Throwable) {
                Rlog.w(TAG, "Failed retiring MMTEL feature during service teardown", t)
            }
        }

        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(periodicRegisterPendingIntent())
        } catch (t: Throwable) {
            Rlog.w(TAG, "Failed cancelling periodic REGISTER alarm", t)
        }

        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (t: Throwable) {
                Rlog.w(TAG, "Failed unregistering IMS broadcast receiver", t)
            }
            receiverRegistered = false
        }

        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Declares MMTEL for every modem slot.
     *
     * `ImsResolver` reads the manifest `MMTEL_FEATURE` metadata only for the ImsService that was
     * the configured device service when it scanned, and falls back to this query otherwise. The
     * manifest path is the one normally taken here, so this exists to keep the features declared
     * when it is not -- for instance when the binding comes from a carrier-config override rather
     * than from the device overlay.
     *
     * Kept in step with the manifest deliberately: it must not advertise a feature the manifest
     * does not, or the set of features depends on how the service happened to be resolved.
     */
    override fun querySupportedImsFeatures(): ImsFeatureConfiguration {
        val slots = try {
            getSystemService(TelephonyManager::class.java)?.activeModemCount ?: 1
        } catch (t: Throwable) {
            Rlog.w(TAG, "Could not read the modem count; declaring MMTEL for slot 0 only", t)
            1
        }

        Rlog.d(TAG, "querySupportedImsFeatures slots=$slots")
        val builder = ImsFeatureConfiguration.Builder()
        for (slot in 0 until slots.coerceAtLeast(1)) {
            builder.addFeature(slot, ImsFeature.FEATURE_MMTEL)
        }
        return builder.build()
    }

    override fun readyForFeatureCreation() {
        Rlog.d(TAG, "readyForFeatureCreation")

        if (instance != null && instance !== this) {
            throw RuntimeException()
        }

        instance = this
    }
}
