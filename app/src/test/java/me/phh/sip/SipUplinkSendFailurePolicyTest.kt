// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipUplinkSendFailurePolicyTest {
    @Test
    fun `an isolated failure is not treated as a lost uplink`() {
        // The caller must keep draining the encoder, so a single dropped datagram cannot be
        // allowed to look like a dead bearer.
        require(!SipUplinkSendFailurePolicy.uplinkIsLost(1))
        require(!SipUplinkSendFailurePolicy.uplinkIsLost(10))
    }

    @Test
    fun `a sustained run is treated as a lost uplink`() {
        val threshold = SipUplinkSendFailurePolicy.CONSECUTIVE_FAILURES_BEFORE_GIVING_UP
        require(!SipUplinkSendFailurePolicy.uplinkIsLost(threshold - 1))
        require(SipUplinkSendFailurePolicy.uplinkIsLost(threshold))
        require(SipUplinkSendFailurePolicy.uplinkIsLost(threshold + 1))
    }

    @Test
    fun `the threshold is about a second of 20ms frames`() {
        // Long enough to ride out a transient error, short enough to notice a dead bearer.
        require(SipUplinkSendFailurePolicy.CONSECUTIVE_FAILURES_BEFORE_GIVING_UP in 25..100)
    }

    @Test
    fun `logging is throttled but never silent at the threshold`() {
        require(SipUplinkSendFailurePolicy.shouldLog(1))
        require(SipUplinkSendFailurePolicy.shouldLog(3))
        require(!SipUplinkSendFailurePolicy.shouldLog(4))
        require(
            SipUplinkSendFailurePolicy.shouldLog(
                SipUplinkSendFailurePolicy.CONSECUTIVE_FAILURES_BEFORE_GIVING_UP,
            ),
        )
    }
}
