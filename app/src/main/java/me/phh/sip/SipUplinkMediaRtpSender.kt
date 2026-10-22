//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress

object SipUplinkMediaRtpSender {
    fun sendStorageFrame(
        logTag: String,
        audioCodec: NegotiatedAudioCodec,
        payloadType: Int,
        sequenceNumber: Int,
        timestamp: Int,
        ssrc: Int,
        storageFrame: ByteArray,
        marker: Boolean,
        rtpSocket: DatagramSocket,
        remoteAddr: InetAddress,
        remotePort: Int,
        frameType: Int,
        frameSize: Int,
        realFrameCount: Int,
        onSendFailure: () -> Int = { 0 },
        onSendSucceeded: () -> Unit = {},
    ): Boolean {
        val packet = SipAmrRtpPayload.buildBandwidthEfficientRtpPacketFromStorageFrame(
            audioCodec = audioCodec,
            payloadType = payloadType,
            sequenceNumber = sequenceNumber,
            timestamp = timestamp,
            ssrc = ssrc,
            storageFrame = storageFrame,
            marker = marker,
        )
        if (packet == null) {
            Rlog.w(logTag, "Failed to build AMR RTP packet: codec=${audioCodec.name} ft=$frameType frameSize=$frameSize")
            return false
        }

        var sent = false
        try {
            if (!RtpPacketSender.send(
                tag = logTag,
                rtpSocket = rtpSocket,
                bytes = packet,
                remoteAddr = remoteAddr,
                remotePort = remotePort,
                label = "RTP packet #$sequenceNumber",
            )) throw IOException("RTP send failed")
            sent = true
            if (realFrameCount < 10) {
                Rlog.d(logTag, "Sent RTP packet #$sequenceNumber ft=$frameType ts=$timestamp payload=${packet.drop(12).take(4).joinToString(" ") { "%02x".format(it) }}... to $remoteAddr:$remotePort")
            }
            if (realFrameCount == 0) {
                Rlog.d(logTag, "First RTP packet full hex: ${packet.joinToString(" ") { "%02x".format(it) }}")
            }
            if (sequenceNumber % 50 == 0 && realFrameCount >= 10) {
                Rlog.d(logTag, "Sent RTP packet #$sequenceNumber ft=$frameType ts=$timestamp to $remoteAddr:$remotePort")
            }
        } catch (e: Exception) {
            val failures = onSendFailure()
            if (SipUplinkSendFailurePolicy.shouldLog(failures)) {
                Rlog.e(
                    logTag,
                    "Failed to send RTP packet #$sequenceNumber " +
                        "(consecutive failures: $failures): ${e.message}",
                    e,
                )
            }
            // Deliberately report success for an isolated failure. The caller stops draining the
            // encoder when this returns false, which would leave encoded output buffered and can
            // then block it from accepting input; a dropped datagram is not worth that. A
            // sustained run is reported through onSendFailure instead.
            return true
        }

        if (sent) onSendSucceeded()
        return true
    }
}
