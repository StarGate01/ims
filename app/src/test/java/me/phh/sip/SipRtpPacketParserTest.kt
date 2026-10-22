// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipRtpPacketParserTest {
    private val speechPt = 96
    private val eventPt = 97

    /** An RTP packet in a deliberately oversized buffer, as the receive loop produces. */
    private fun packet(payloadType: Int, version: Int = 2, bufferSize: Int = 2048): ByteArray =
        ByteArray(bufferSize).also {
            it[0] = ((version shl 6) and 0xff).toByte()
            it[1] = (payloadType and 0x7f).toByte()
        }

    private fun disposition(packet: ByteArray, length: Int) =
        SipRtpPacketParser.disposition(
            packet = packet,
            length = length,
            speechPayloadType = speechPt,
            telephoneEventPayloadType = eventPt,
        )

    @Test
    fun `the negotiated speech type is decoded`() {
        require(disposition(packet(speechPt), 32) == SipRtpPacketParser.Disposition.DECODE_SPEECH)
    }

    @Test
    fun `a telephone-event packet is skipped rather than decoded as speech`() {
        // The regression this guards: the payload type was read for logging only, so an event
        // packet went to the AMR depacketiser on the chance its bytes happened to parse.
        require(
            disposition(packet(eventPt), 16) == SipRtpPacketParser.Disposition.SKIP_TELEPHONE_EVENT,
        )
    }

    @Test
    fun `a datagram shorter than the RTP header is dropped`() {
        // The buffer is 2048 bytes regardless, so only the reported length can reveal this.
        for (length in 0..11) {
            require(
                disposition(packet(speechPt), length) == SipRtpPacketParser.Disposition.DROP_NOT_RTP,
            ) { "length $length" }
        }
        require(disposition(packet(speechPt), 12) == SipRtpPacketParser.Disposition.DECODE_SPEECH)
    }

    @Test
    fun `a non-RTP version is dropped`() {
        for (version in listOf(0, 1, 3)) {
            require(
                disposition(packet(speechPt, version = version), 32)
                    == SipRtpPacketParser.Disposition.DROP_NOT_RTP,
            ) { "version $version" }
        }
    }

    @Test
    fun `an unexpected payload type is still attempted`() {
        // Deliberately lenient: a dialog whose negotiated type does not match what the peer sends
        // must not go silent, and the depacketiser rejects what it cannot parse.
        require(
            disposition(packet(100), 32) == SipRtpPacketParser.Disposition.DECODE_UNEXPECTED_TYPE,
        )
    }

    @Test
    fun `payloadType reports null below the header size and ignores the marker bit`() {
        require(SipRtpPacketParser.payloadType(packet(speechPt), 11) == null)
        val marked = packet(speechPt).also { it[1] = (0x80 or speechPt).toByte() }
        require(SipRtpPacketParser.payloadType(marked, 32) == speechPt)
    }
}
