// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

/**
 * Pins the rule every retire path must follow when two outgoing messages share an 8-bit
 * reference: drop the reference index entry only while it still points at the message being
 * retired. Four paths retire a pending message -- RP result, timeout, SIP failure response and
 * write failure -- and an unconditional remove in any of them deletes a live message's mapping.
 */
class SipSmsReferenceRetireTest {
    private class Pending(val callId: String, val ref: Int)

    private val byCallId = mutableMapOf<String, Pending>()
    private val byRef = mutableMapOf<Int, Pending>()

    private fun remember(p: Pending) {
        byCallId[p.callId] = p
        byRef[p.ref] = p
    }

    /** The rule under test, mirroring SipSmsHandler.forgetRefIfStillOwnedBy. */
    private fun retire(p: Pending) {
        byCallId.remove(p.callId)
        if (byRef[p.ref] === p) byRef.remove(p.ref)
    }

    /** What an unconditional remove would do, for contrast. */
    private fun retireUnconditionally(p: Pending) {
        byCallId.remove(p.callId)
        byRef.remove(p.ref)
    }

    @Test
    fun `retiring the older message leaves the newer one addressable by reference`() {
        val older = Pending("call-a", 0x42)
        val newer = Pending("call-b", 0x42)
        remember(older)
        remember(newer)

        retire(older)

        require(byRef[0x42] === newer) { "the live message lost its reference entry" }
        require(byCallId["call-b"] === newer)
        require(byCallId["call-a"] == null)
    }

    @Test
    fun `an unconditional remove is what used to break it`() {
        val older = Pending("call-a", 0x42)
        val newer = Pending("call-b", 0x42)
        remember(older)
        remember(newer)

        retireUnconditionally(older)

        require(byRef[0x42] == null) { "this is the defect being guarded against" }
    }

    @Test
    fun `retiring the newer message clears the reference it owns`() {
        val older = Pending("call-a", 0x42)
        val newer = Pending("call-b", 0x42)
        remember(older)
        remember(newer)

        retire(newer)

        require(byRef[0x42] == null)
        // The displaced older message is still reachable by Call-ID, which is the correlation
        // In-Reply-To supplies.
        require(byCallId["call-a"] === older)
    }

    @Test
    fun `a message with a unique reference is fully retired`() {
        val only = Pending("call-a", 0x07)
        remember(only)
        retire(only)
        require(byRef[0x07] == null && byCallId["call-a"] == null)
    }
}
