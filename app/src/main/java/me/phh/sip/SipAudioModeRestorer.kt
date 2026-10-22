//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.content.Context
import android.media.AudioManager
import android.telephony.Rlog

object SipAudioModeRestorer {
    fun restoreAfterImsCall(
        logTag: String,
        context: Context,
        reason: String,
        previousMode: Int? = null
    ) {
        val audioManager = try {
            context.getSystemService(AudioManager::class.java)
        } catch (t: Throwable) {
            Rlog.d(logTag, "Audio mode restore skipped; AudioManager unavailable: $reason", t)
            return
        }

        val currentMode = audioManager.mode

        // Only release a mode this stack actually claimed. previousMode is what the mode was
        // before the media path started, so MODE_IN_COMMUNICATION there means somebody else --
        // the telephony framework, for a call reported as using VoIP audio mode -- already owned
        // it and is driving its own reset. Forcing MODE_NORMAL here would race that reset, which
        // AudioService reports as "Timeout waiting for audio mode reset", and would be the
        // release-side mirror of the acquisition the uplink starter deliberately skips.
        if (previousMode == AudioManager.MODE_IN_COMMUNICATION) {
            Rlog.d(
                logTag,
                "Audio mode was already MODE_IN_COMMUNICATION before this call; leaving it to " +
                    "its owner: reason=$reason currentMode=$currentMode",
            )
            return
        }

        val wantedMode = when (previousMode ?: currentMode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_RINGTONE -> AudioManager.MODE_NORMAL
            else -> previousMode ?: currentMode
        }

        if (currentMode == wantedMode) {
            Rlog.d(logTag, "Audio mode restore not needed: reason=$reason currentMode=$currentMode previousMode=$previousMode")
            return
        }

        Rlog.d(
            logTag,
            "Restoring audio mode after IMS call: reason=$reason " +
                "currentMode=$currentMode previousMode=$previousMode wantedMode=$wantedMode",
        )
        try {
            audioManager.clearCommunicationDevice()
        } catch (t: Throwable) {
            Rlog.d(logTag, "clearCommunicationDevice failed during IMS audio restore: $reason", t)
        }
        try {
            audioManager.mode = wantedMode
        } catch (t: Throwable) {
            Rlog.d(logTag, "Setting audio mode failed during IMS audio restore: $reason", t)
        }
    
    }
}
