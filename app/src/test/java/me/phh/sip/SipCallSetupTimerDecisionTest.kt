// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipCallSetupTimerDecisionTest {
    private fun decide(
        statusCode: Int,
        callId: String = "call-1",
        armedCallId: String? = null,
        ringbackCallId: String? = null,
    ) = SipCallSetupTimerDecision.onOutgoingInviteResponse(
        statusCode = statusCode,
        callId = callId,
        armedCallId = armedCallId,
        ringbackCallId = ringbackCallId,
    )

    @Test
    fun `100 Trying arms the final-response deadline`() {
        require(decide(100) == SipCallSetupTimerAction.START_INVITE_TIMEOUT)
    }

    @Test
    fun `183 Session Progress arms the deadline instead of cancelling it`() {
        // The regression this guards: 183 used to fall into the "> 100" arm and cancel the
        // deadline outright, so a 183 never followed by a final response left the call pending.
        require(decide(183) == SipCallSetupTimerAction.START_INVITE_TIMEOUT)
    }

    @Test
    fun `every other provisional also keeps a deadline`() {
        for (code in listOf(101, 181, 182, 199)) {
            require(decide(code) == SipCallSetupTimerAction.START_INVITE_TIMEOUT) { "code $code" }
        }
    }

    @Test
    fun `a provisional does not re-arm a deadline already running for the same call`() {
        // A network emitting 183 periodically must not be able to extend the deadline forever.
        require(decide(183, armedCallId = "call-1") == SipCallSetupTimerAction.KEEP)
        require(decide(100, armedCallId = "call-1") == SipCallSetupTimerAction.KEEP)
    }

    @Test
    fun `a provisional for a different call arms its own deadline`() {
        require(
            decide(183, callId = "call-2", armedCallId = "call-1")
                == SipCallSetupTimerAction.START_INVITE_TIMEOUT,
        )
    }

    @Test
    fun `180 Ringing switches to the ringback deadline`() {
        require(decide(180) == SipCallSetupTimerAction.START_RINGBACK_TIMEOUT)
        // Also when a final-response deadline is already running.
        require(decide(180, armedCallId = "call-1") == SipCallSetupTimerAction.START_RINGBACK_TIMEOUT)
    }

    @Test
    fun `a provisional after 180 does not downgrade the ringback deadline`() {
        // Some cores interleave 183 with 180; the shorter ringback deadline must survive.
        require(
            decide(183, armedCallId = "call-1", ringbackCallId = "call-1")
                == SipCallSetupTimerAction.KEEP,
        )
    }

    @Test
    fun `final responses cancel the deadline`() {
        for (code in listOf(200, 380, 404, 486, 500, 603)) {
            require(decide(code, armedCallId = "call-1") == SipCallSetupTimerAction.CANCEL) {
                "code $code"
            }
        }
    }
}
