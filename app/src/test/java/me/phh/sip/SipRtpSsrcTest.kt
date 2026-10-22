// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipRtpSsrcTest {
    private val codec = SipAudioCodecs.AMR_NB

    private fun ssrcOf(packet: ByteArray): Int =
        ((packet[8].toInt() and 0xff) shl 24) or
            ((packet[9].toInt() and 0xff) shl 16) or
            ((packet[10].toInt() and 0xff) shl 8) or
            (packet[11].toInt() and 0xff)

    @Test
    fun `the SSRC given to a speech packet appears in its header`() {
        val packet = SipAmrRtpPayload.buildNoDataRtpPacket(
            audioCodec = codec,
            payloadType = 96,
            sequenceNumber = 1,
            timestamp = 160,
            ssrc = 0x1234abcd,
        )
        require(ssrcOf(packet) == 0x1234abcd) { "got 0x%08x".format(ssrcOf(packet)) }
    }

    @Test
    fun `a telephone-event packet carries the same SSRC as the speech stream`() {
        // RFC 4733 events share the speech stream's SSRC, so both builders must be given the
        // same value; they used to hardcode it, which is what made that accidentally true.
        val ssrc = 0x0badf00d
        val speech = SipAmrRtpPayload.buildNoDataRtpPacket(
            audioCodec = codec,
            payloadType = 96,
            sequenceNumber = 1,
            timestamp = 160,
            ssrc = ssrc,
        )
        val event = SipDtmfRtpPacketBuilder.buildTelephoneEventPacket(
            payloadType = 97,
            sequenceNumber = 2,
            timestamp = 320,
            ssrc = ssrc,
            event = 5,
            duration = 160,
            repeatIndex = 0,
        )
        require(ssrcOf(speech) == ssrc)
        require(ssrcOf(event) == ssrc)
    }

    @Test
    fun `a negative SSRC survives the round trip`() {
        // SecureRandom().nextInt() covers the whole 32-bit range; the header is unsigned.
        val packet = SipAmrRtpPayload.buildNoDataRtpPacket(
            audioCodec = codec,
            payloadType = 96,
            sequenceNumber = 1,
            timestamp = 160,
            ssrc = -1,
        )
        require(ssrcOf(packet) == -1)
        require(packet.copyOfRange(8, 12).all { it == 0xff.toByte() })
    }

    @Test
    fun `the rest of the header is unchanged`() {
        val packet = SipAmrRtpPayload.buildNoDataRtpPacket(
            audioCodec = codec,
            payloadType = 96,
            sequenceNumber = 0x0102,
            timestamp = 0x03040506,
            ssrc = 0,
            marker = true,
        )
        require(packet[0] == 0x80.toByte())            // version 2, no padding/extension/CSRC
        require(packet[1] == (0x80 or 96).toByte())    // marker + payload type
        require(packet[2] == 0x01.toByte() && packet[3] == 0x02.toByte())
        require(packet.copyOfRange(4, 8).contentEquals(byteArrayOf(3, 4, 5, 6)))
    }
}
