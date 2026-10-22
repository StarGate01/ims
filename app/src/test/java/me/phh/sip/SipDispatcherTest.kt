// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Test

class SipDispatcherTest {
    private fun response(callId: String, cseq: String): SipResponse = SipResponse(
        statusCode = 200,
        statusString = "OK",
        headersParam = mapOf(
            "call-id" to listOf(callId),
            "cseq" to listOf(cseq),
        ),
        autofill = false,
    )

    @Test
    fun `completed callback does not remove replacement`() {
        val dispatcher = SipDispatcher("test")
        var replacementCalled = false
        dispatcher.setResponseCallback("dialog") {
            dispatcher.setResponseCallback("dialog") {
                replacementCalled = true
                true
            }
            true
        }

        dispatcher.handleResponse(response("dialog", "1 OPTIONS"))
        dispatcher.handleResponse(response("dialog", "2 OPTIONS"))

        require(replacementCalled)
    }

    @Test
    fun `transaction callback requires exact cseq and method`() {
        val dispatcher = SipDispatcher("test")
        var fallbackCalls = 0
        var transactionCalls = 0
        dispatcher.setResponseCallback("dialog") {
            fallbackCalls++
            false
        }
        dispatcher.setResponseCallback("dialog", 7, SipMethod.INVITE) {
            transactionCalls++
            true
        }

        dispatcher.handleResponse(response("dialog", "6 INVITE"))
        dispatcher.handleResponse(response("dialog", "7 UPDATE"))
        dispatcher.handleResponse(response("dialog", "7 INVITE"))

        require(fallbackCalls == 2)
        require(transactionCalls == 1)
    }

    @Test
    fun `closed transport drops only its request writers`() {
        val dispatcher = SipDispatcher("test")
        val firstWriter = ByteArrayOutputStream()
        val secondWriter = ByteArrayOutputStream()
        val firstRequest = "OPTIONS sip:test@example.test SIP/2.0\r\n" +
            "Call-ID: first\r\nCSeq: 1 OPTIONS\r\nContent-Length: 0\r\n\r\n"
        val secondRequest = "OPTIONS sip:test@example.test SIP/2.0\r\n" +
            "Call-ID: second\r\nCSeq: 2 OPTIONS\r\nContent-Length: 0\r\n\r\n"

        dispatcher.parseMessage(
            ByteArrayInputStream(firstRequest.toByteArray()).sipReader(),
            firstWriter,
        )
        dispatcher.parseMessage(
            ByteArrayInputStream(secondRequest.toByteArray()).sipReader(),
            secondWriter,
        )
        dispatcher.removeWritersFor(firstWriter)

        require(!dispatcher.hasWriterForCallId("first"))
        require(dispatcher.hasWriterForCallId("second"))
    }

    private fun request(method: String, callId: String): ByteArray =
        (
            "$method sip:peer@example.test SIP/2.0\r\n" +
                "Via: SIP/2.0/TCP 192.0.2.1:5060;branch=z9hG4bK1\r\n" +
                "From: <sip:peer@example.test>;tag=a\r\n" +
                "To: <sip:me@example.test>\r\n" +
                "Call-ID: $callId\r\n" +
                "CSeq: 1 $method\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n"
            ).toByteArray()

    private fun statusLineFor(method: String): String {
        val dispatcher = SipDispatcher("test")
        val out = ByteArrayOutputStream()
        val reader = ByteArrayInputStream(request(method, "c-$method")).sipReader()
        dispatcher.parseMessage(reader, out)
        return out.toByteArray().decodeToString().lineSequence().first().trim()
    }

    @Test
    fun `an unhandled OPTIONS is acknowledged`() {
        // RFC 3261 section 11.2: 200 OK is the correct answer, so the default must stay.
        require(statusLineFor("OPTIONS").startsWith("SIP/2.0 200")) { statusLineFor("OPTIONS") }
    }

    @Test
    fun `an unhandled NOTIFY is acknowledged`() {
        // RFC 6665 section 4.4.1 requires answering it, and this stack subscribes to the reg
        // event: refusing a NOTIFY would break its own subscription.
        require(statusLineFor("NOTIFY").startsWith("SIP/2.0 200")) { statusLineFor("NOTIFY") }
    }

    @Test
    fun `an unhandled SUBSCRIBE is refused rather than silently accepted`() {
        // 200 OK would promise a subscription this stack never sends a NOTIFY for.
        val line = statusLineFor("SUBSCRIBE")
        require(line.startsWith("SIP/2.0 501")) { line }
        require(line.contains("Not Implemented")) { line }
    }

    @Test
    fun `an unhandled REGISTER is refused`() {
        require(statusLineFor("REGISTER").startsWith("SIP/2.0 501")) { statusLineFor("REGISTER") }
    }
}
