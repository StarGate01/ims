// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.os.Handler
import android.telephony.Rlog

internal class SipCallSetupTimers(
    private val tag: String,
    private val handler: Handler,
    private val policy: () -> SipCallSetupTimerPolicy,
    private val onOutgoingTimeout: (String, String) -> Unit,
    private val onIncomingTimeout: (String) -> Unit,
) {
    private var outgoingCallId: String? = null
    private var outgoingGeneration = 0
    private var outgoingRingbackCallId: String? = null
    private var incomingCallId: String? = null
    private var incomingGeneration = 0

    fun onOutgoingInviteResponse(callId: String, statusCode: Int) {
        val action = SipCallSetupTimerDecision.onOutgoingInviteResponse(
            statusCode = statusCode,
            callId = callId,
            armedCallId = outgoingCallId,
            ringbackCallId = outgoingRingbackCallId,
        )
        when (action) {
            SipCallSetupTimerAction.START_INVITE_TIMEOUT -> startOutgoing(
                callId,
                policy().inviteTimeoutMs,
                "INVITE response timeout",
            )
            SipCallSetupTimerAction.START_RINGBACK_TIMEOUT -> {
                cancelOutgoing("180 Ringing")
                outgoingRingbackCallId = callId
                startOutgoing(
                    callId,
                    policy().ringbackTimeoutMs,
                    "ringback timeout",
                )
            }
            SipCallSetupTimerAction.CANCEL -> {
                outgoingRingbackCallId = null
                cancelOutgoing("INVITE response $statusCode")
            }
            SipCallSetupTimerAction.KEEP ->
                Rlog.d(
                    tag,
                    "Keeping outgoing call setup timer across provisional $statusCode: " +
                        "callId=$callId",
                )
        }
    }

    fun startIncoming(callId: String) {
        cancelIncoming("new incoming INVITE")
        val generation = ++incomingGeneration
        incomingCallId = callId
        val delayMs = policy().ringingTimeoutMs.coerceAtLeast(1L)
        Rlog.d(tag, "Starting incoming ringing timer: callId=$callId delayMs=$delayMs")
        handler.postDelayed({
            if (incomingGeneration == generation && incomingCallId == callId) {
                incomingCallId = null
                onIncomingTimeout(callId)
            }
        }, delayMs)
    }

    fun cancelAll(reason: String) {
        cancelOutgoing(reason)
        cancelIncoming(reason)
    }

    private fun startOutgoing(callId: String, delayMs: Long, reason: String) {
        val generation = ++outgoingGeneration
        outgoingCallId = callId
        val boundedDelayMs = delayMs.coerceAtLeast(1L)
        Rlog.d(
            tag,
            "Starting outgoing call setup timer: callId=$callId " +
                "delayMs=$boundedDelayMs reason=$reason",
        )
        handler.postDelayed({
            if (outgoingGeneration == generation && outgoingCallId == callId) {
                outgoingCallId = null
                onOutgoingTimeout(callId, reason)
            }
        }, boundedDelayMs)
    }

    private fun cancelOutgoing(reason: String) {
        outgoingRingbackCallId = null
        if (outgoingCallId != null) {
            Rlog.d(tag, "Cancelling outgoing call setup timer: $reason")
        }
        outgoingCallId = null
        outgoingGeneration++
    }

    private fun cancelIncoming(reason: String) {
        if (incomingCallId != null) {
            Rlog.d(tag, "Cancelling incoming ringing timer: $reason")
        }
        incomingCallId = null
        incomingGeneration++
    }
}
