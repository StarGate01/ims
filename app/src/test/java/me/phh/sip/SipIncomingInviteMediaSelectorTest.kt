// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipIncomingInviteMediaSelectorTest {
    private fun select(attributes: List<String>) =
        SipIncomingInviteMediaSelector.select(
            sdp = listOf("m=audio 1234 RTP/AVP 96 97"),
            attributes = attributes,
            incomingCallId = "call-1",
            amrWbMediaCodecAvailable = false,
            logTag = "test",
        )

    @Test
    fun `a bandwidth-efficient offer is accepted`() {
        val selection = select(
            listOf(
                "rtpmap:96 AMR/8000/1",
                "fmtp:96 mode-set=7;octet-align=0;max-red=0",
                "rtpmap:97 telephone-event/8000",
            ),
        )
        require(selection != null)
        require(selection.amrTrack == 96)
        require(selection.dtmfTrack == 97)
    }

    @Test
    fun `an offer with only octet-align=1 is refused`() {
        // The regression this guards: octet-align=1 used to be a sort preference, so a single
        // incompatible payload was selected anyway, answered with its own octet-align=1 fmtp, and
        // then fed bandwidth-efficient RTP -- a call that connects with undecodable audio. The
        // caller turns null into 488 Not Acceptable Here.
        val selection = select(
            listOf(
                "rtpmap:96 AMR/8000/1",
                "fmtp:96 mode-set=7;octet-align=1;max-red=0",
                "rtpmap:97 telephone-event/8000",
            ),
        )
        require(selection == null)
    }

    @Test
    fun `a compatible payload is chosen over an incompatible one`() {
        val selection = select(
            listOf(
                "rtpmap:96 AMR/8000/1",
                "fmtp:96 octet-align=1",
                "rtpmap:98 AMR/8000/1",
                "fmtp:98 mode-set=7;octet-align=0",
                "rtpmap:97 telephone-event/8000",
            ),
        )
        require(selection != null)
        require(selection.amrTrack == 98)
    }

    @Test
    fun `the exclusion is case insensitive`() {
        require(
            select(
                listOf(
                    "rtpmap:96 AMR/8000/1",
                    "fmtp:96 Octet-Align=1",
                    "rtpmap:97 telephone-event/8000",
                ),
            ) == null,
        )
    }

    @Test
    fun `an AMR payload with no fmtp stays eligible`() {
        // RFC 4867 defaults octet-align to 0, which is exactly what this stack sends, so an
        // fmtp-less payload must not be excluded -- only deprioritised.
        val selection = select(
            listOf(
                "rtpmap:96 AMR/8000/1",
                "rtpmap:97 telephone-event/8000",
            ),
        )
        require(selection != null)
        require(selection.amrTrack == 96)
    }

    @Test
    fun `an fmtp-less payload is still preferred over an octet-align=1 one`() {
        val selection = select(
            listOf(
                "rtpmap:96 AMR/8000/1",
                "fmtp:96 octet-align=1",
                "rtpmap:98 AMR/8000/1",
                "rtpmap:97 telephone-event/8000",
            ),
        )
        require(selection != null)
        require(selection.amrTrack == 98)
    }

    @Test
    fun `a missing telephone-event track still refuses the offer`() {
        require(
            select(
                listOf(
                    "rtpmap:96 AMR/8000/1",
                    "fmtp:96 octet-align=0",
                ),
            ) == null,
        )
    }
}
