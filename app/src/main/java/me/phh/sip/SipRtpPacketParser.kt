//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

object SipRtpPacketParser {
    /** An RTP fixed header is 12 bytes before any CSRC list or extension. */
    const val RTP_MIN_HEADER_BYTES = 12

    /**
     * The payload type, or null if [length] is too short for the datagram to be RTP at all.
     *
     * Takes the received length rather than trusting the backing buffer: the receive loop reuses
     * an oversized array, so reading the header of a short datagram would otherwise return bytes
     * left over from an earlier packet.
     */
    fun payloadType(packet: ByteArray, length: Int): Int? {
        if (length < RTP_MIN_HEADER_BYTES) return null
        return packet[1].toUByte().toInt() and 0x7f
    }

    /** True when the version field says RTP version 2 (RFC 3550). */
    fun hasRtpVersion2(packet: ByteArray, length: Int): Boolean {
        if (length < RTP_MIN_HEADER_BYTES) return false
        return ((packet[0].toUByte().toInt() shr 6) and 0x03) == 2
    }

    /** What the downlink should do with a received datagram. */
    enum class Disposition {
        /** Hand the payload to the speech decoder. */
        DECODE_SPEECH,

        /** A telephone-event packet: part of this stream, but not speech. */
        SKIP_TELEPHONE_EVENT,

        /** Too short, or not RTP version 2. */
        DROP_NOT_RTP,

        /**
         * An unexpected payload type. Decoding is still attempted, because a dialog whose
         * negotiated speech payload type does not match what the peer actually sends would
         * otherwise go silent, and the AMR depacketiser rejects what it cannot parse anyway.
         */
        DECODE_UNEXPECTED_TYPE,
    }

    /**
     * Classifies a received datagram against the payload types this dialog negotiated.
     *
     * The point is to keep telephone-event packets out of the speech decoder: they share the
     * stream, and their four payload bytes can satisfy a permissive AMR parse.
     */
    fun disposition(
        packet: ByteArray,
        length: Int,
        speechPayloadType: Int,
        telephoneEventPayloadType: Int,
    ): Disposition {
        if (!hasRtpVersion2(packet, length)) return Disposition.DROP_NOT_RTP
        return when (payloadType(packet, length)) {
            speechPayloadType -> Disposition.DECODE_SPEECH
            telephoneEventPayloadType -> Disposition.SKIP_TELEPHONE_EVENT
            else -> Disposition.DECODE_UNEXPECTED_TYPE
        }
    }
}
