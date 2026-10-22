//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.telephony.Rlog

object SipAudioRecordStarter {
    fun startForImsUplink(
        logTag: String,
        audioManager: AudioManager,
        audioRecord: AudioRecord,
        encoder: MediaCodec,
    ): Int? {
        val previousAudioMode = audioManager.mode
        // Claim the mode only when it is not already correct. The audio policy honours a
        // communication-device request solely from the UID that owns the audio mode, so taking
        // ownership from the telephony framework -- which sets MODE_IN_COMMUNICATION itself for
        // calls reported as VoIP audio mode -- would silently disable the platform's own in-call
        // route controls. Mirrors the guard on the downlink side.
        if (previousAudioMode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }

        // Samsung's HAL starts a modem voice call when the mode goes to MODE_IN_CALL at dial, and
        // that call keeps the voice path even after the mode becomes MODE_IN_COMMUNICATION -- the
        // uplink then carries the modem's silent baseband stream instead of the microphone. This
        // vendor key stops it. Other HALs ignore an unknown key, so it is safe to send
        // unconditionally. Normally redundant now that MODE_IN_CALL no longer happens at all for
        // these calls, and kept as a guard for any path that still reaches it.
        try {
            audioManager.setParameters("g_call_state=1")
        } catch (t: Throwable) {
            Rlog.d(logTag, "Vendor g_call_state parameter rejected", t)
        }

        try {
            audioRecord.startRecording()
        } catch (t: Throwable) {
            Rlog.e(logTag, "AudioRecord.startRecording failed", t)
            try {
                audioRecord.release()
            } catch (_: Throwable) {
            }
            try {
                encoder.stop()
            } catch (_: Throwable) {
            }
            try {
                encoder.release()
            } catch (_: Throwable) {
            }
            return null
        }
        Rlog.d(
            logTag,
            "AudioRecord started, state=${audioRecord.recordingState} " +
                "audioMode=${audioManager.mode} (was $previousAudioMode) " +
                "preferredDevice=${audioRecord.preferredDevice?.type}"
        )
        return previousAudioMode
    }
}
