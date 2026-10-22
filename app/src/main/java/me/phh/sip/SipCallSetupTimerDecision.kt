//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

/**
 * What an outgoing INVITE response should do to the call-setup deadline.
 *
 * Kept pure so the provisional-response rules can be unit tested without a Handler.
 */
internal enum class SipCallSetupTimerAction {
    /** Arm the final-response deadline. */
    START_INVITE_TIMEOUT,

    /** Replace any running deadline with the shorter ringback deadline. */
    START_RINGBACK_TIMEOUT,

    /** The transaction is finished; drop the deadline. */
    CANCEL,

    /** Leave the running deadline exactly as it is. */
    KEEP,
}

internal object SipCallSetupTimerDecision {

    /**
     * Decides what an INVITE response does to the outgoing call-setup deadline.
     *
     * RFC 3261: only a final response (>= 200) completes an INVITE client transaction. Every
     * provisional response must therefore leave a final-response deadline running, or a call that
     * is answered with a provisional and then abandoned never fails.
     *
     * - `180 Ringing` is special: the callee is being alerted, so the shorter ringback deadline
     *   replaces the broad final-response one.
     * - Any other provisional (`100 Trying`, `183 Session Progress`, `181`, `182`, `199`) arms the
     *   final-response deadline **once**. It is not re-armed while a deadline is already running
     *   for this call, so a network emitting provisionals periodically cannot extend the deadline
     *   indefinitely, and it never downgrades a ringback deadline already in effect.
     *
     * @param armedCallId the call the running deadline belongs to, or null if none is running.
     * @param ringbackCallId the call whose ringback deadline is in effect, or null.
     */
    fun onOutgoingInviteResponse(
        statusCode: Int,
        callId: String,
        armedCallId: String?,
        ringbackCallId: String?,
    ): SipCallSetupTimerAction = when {
        statusCode >= 200 -> SipCallSetupTimerAction.CANCEL
        statusCode == 180 -> SipCallSetupTimerAction.START_RINGBACK_TIMEOUT
        statusCode >= 100 -> when {
            ringbackCallId == callId -> SipCallSetupTimerAction.KEEP
            armedCallId == callId -> SipCallSetupTimerAction.KEEP
            else -> SipCallSetupTimerAction.START_INVITE_TIMEOUT
        }
        // The parser only accepts 100..699, so this is unreachable; fail safe by keeping the
        // deadline rather than cancelling it on an unparseable code.
        else -> SipCallSetupTimerAction.KEEP
    }
}
