// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog
import java.io.OutputStream
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SIP transaction callback router.
 *
 * This is intentionally small and behaviour-compatible with the dispatcher code
 * that currently lives in SipHandler. The first user should be SipHandler
 * itself; later transport classes can feed parsed messages into this class
 * without owning call/SMS/registration state directly.
 */
internal class SipDispatcher(
    private val tag: String,
) {
    private val lock = ReentrantLock()
    private var requestCallbacks: Map<SipMethod, (SipRequest) -> Int> = emptyMap()
    private data class ResponseTransactionKey(
        val callId: String,
        val cseqNumber: Int,
        val method: SipMethod,
    )

    private var responseCallbacks: Map<String, (SipResponse) -> Boolean> = emptyMap()
    private var transactionResponseCallbacks:
        Map<ResponseTransactionKey, (SipResponse) -> Boolean> = emptyMap()

    /**
     * SIP responses must be written back on the same transport flow that
     * delivered the request. This matters for incoming INVITE over the TCP
     * server socket: writing 180/200 to the registration socket may be ignored
     * by the P-CSCF.
     */
    private val requestWriters = ConcurrentHashMap<String, OutputStream>()

    fun setRequestCallback(method: SipMethod, cb: (SipRequest) -> Int) {
        lock.withLock { requestCallbacks += method to cb }
    }

    fun setResponseCallback(callId: String, cb: (SipResponse) -> Boolean) {
        lock.withLock { responseCallbacks += callId to cb }
    }

    fun setResponseCallback(
        callId: String,
        cseqNumber: Int,
        method: SipMethod,
        cb: (SipResponse) -> Boolean,
    ) {
        val key = ResponseTransactionKey(callId, cseqNumber, method)
        lock.withLock { transactionResponseCallbacks += key to cb }
    }

    fun removeResponseCallback(callId: String) {
        lock.withLock {
            responseCallbacks -= callId
            transactionResponseCallbacks = transactionResponseCallbacks.filterKeys {
                it.callId != callId
            }
        }
    }

    fun removeResponseCallback(
        callId: String,
        cseqNumber: Int,
        method: SipMethod,
    ) {
        val key = ResponseTransactionKey(callId, cseqNumber, method)
        lock.withLock { transactionResponseCallbacks -= key }
    }

    fun clearCallbacks() {
        lock.withLock {
            requestCallbacks = emptyMap()
            responseCallbacks = emptyMap()
            transactionResponseCallbacks = emptyMap()
        }
    }

    fun clearWriters() {
        requestWriters.clear()
    }

    fun removeWriterForCallId(callId: String) {
        requestWriters.remove(callId)
    }

    fun removeWritersFor(writer: OutputStream) {
        requestWriters.forEach { (callId, registeredWriter) ->
            if (registeredWriter === writer) {
                requestWriters.remove(callId, registeredWriter)
            }
        }
    }

    fun writerForCallId(callId: String): OutputStream? = requestWriters[callId]

    fun hasWriterForCallId(callId: String): Boolean = requestWriters.containsKey(callId)

    fun parseMessage(reader: SipReader, writer: OutputStream): Boolean {
        val msg = try {
            reader.parseMessage()
        } catch (e: SipParseException) {
            Rlog.w(tag, "Rejecting malformed SIP message: ${e.message}")
            return false
        } catch (e: SocketException) {
            Rlog.d(tag, "Got exception $e")
            if ("$e" == "java.net.SocketException: Try again") {
                // We sometimes seem to get EAGAIN.
                return true
            }
            throw e
        }

        Rlog.d(tag, "Received ${msg?.safeLogSummary() ?: "end-of-stream"}")

        if (msg is SipResponse) {
            return handleResponse(msg)
        }

        if (msg == null && reader.hasMoreInput()) {
            // An empty line where a message was expected: a keepalive pong (RFC 5626) or one of
            // the stray CRLFs RFC 3261 section 7.5 permits between messages. The stream is still
            // healthy, so keep reading instead of tearing the connection down.
            Rlog.d(tag, "Skipping an empty line before the next SIP message")
            return true
        }

        if (msg !is SipRequest) {
            Rlog.d(tag, "Got invalid message! Closing socket (except main)")
            return false
        }

        msg.headers["call-id"]?.getOrNull(0)?.let { callId ->
            requestWriters[callId] = writer
        }

        val requestCb = lock.withLock { requestCallbacks[msg.method] }
        var status = defaultStatusForUnhandled(msg.method)
        if (requestCb != null) {
            status = try {
                requestCb(msg)
            } catch (t: Throwable) {
                Rlog.e(
                    tag,
                    "Request handler for ${msg.method} crashed; replying 500 and keeping SIP transport alive",
                    t,
                )
                500
            }
        }

        if (status == 0) return true

        val responseHeaders = msg.headers.filter { (k, _) ->
            k in listOf("cseq", "via", "from", "to", "call-id")
        } + when (status) {
            486 -> "Reason: Q.850;cause=17;text=\"User busy\"".toSipHeadersMap()
            else -> emptyMap()
        }

        val reply = SipResponse(
            statusCode = status,
            statusString = when (status) {
                100 -> "Trying"
                200 -> "OK"
                480 -> "Temporarily Unavailable"
                481 -> "Call/Transaction Does Not Exist"
                486 -> "Busy Here"
                487 -> "Request Terminated"
                488 -> "Not Acceptable Here"
                491 -> "Request Pending"
                501 -> "Not Implemented"
                500 -> "Server Internal Error"
                603 -> "Decline"
                else -> "ERROR"
            },
            headersParam = responseHeaders,
        )

        Rlog.d(tag, "Replying with ${reply.safeLogSummary()}")
        return SipMessageWriter.write(
            tag = tag,
            writer = writer,
            bytes = reply.toByteArray(),
            label = "dispatcher ${msg.method} response ${reply.statusCode}",
        )
    }

    fun handleResponse(response: SipResponse): Boolean {
        val callId = response.headers["call-id"]?.get(0)
        if (callId == null) {
            // Message without call-id should never happen, close connection.
            return false
        }

        val transactionKey = responseTransactionKey(response, callId)
        val transactionCb = transactionKey?.let { key ->
            lock.withLock { transactionResponseCallbacks[key] }
        }
        val responseCb = transactionCb ?: lock.withLock { responseCallbacks[callId] }
            ?: return true

        if (responseCb(response)) {
            lock.withLock {
                if (transactionKey != null && transactionCb != null) {
                    if (transactionResponseCallbacks[transactionKey] === responseCb) {
                        transactionResponseCallbacks -= transactionKey
                    }
                } else if (responseCallbacks[callId] === responseCb) {
                    responseCallbacks -= callId
                }
            }
        }
        return true
    }
    /**
     * What to answer a request this stack recognises but registers no handler for.
     *
     * A method outside [SipMethod] never reaches here: the parser returns a SipCommonMessage for
     * it and the caller closes the connection. The unhandled set is therefore just the methods
     * this stack parses but does not act on, and 200 OK is not wrong for all of them:
     *
     * - OPTIONS: RFC 3261 section 11.2 expects 200 OK, so a bare acknowledgement is correct.
     * - NOTIFY: RFC 6665 section 4.4.1 requires the subscriber to answer it, and this stack
     *   subscribes to the reg event, so answering anything else would break its own subscription.
     * - SUBSCRIBE: 200 OK would claim a subscription this stack will never send a NOTIFY for,
     *   leaving the peer waiting. 501 says plainly that it is not implemented.
     * - REGISTER: a UE is not a registrar. Unreachable in practice, and 501 is honest.
     */
    private fun defaultStatusForUnhandled(method: SipMethod): Int = when (method) {
        SipMethod.SUBSCRIBE, SipMethod.REGISTER -> 501
        else -> 200
    }

    private fun responseTransactionKey(
        response: SipResponse,
        callId: String,
    ): ResponseTransactionKey? {
        val cseq = response.headers["cseq"]?.firstOrNull() ?: return null
        val parts = cseq.trim().split(Regex("\\s+"), limit = 2)
        val cseqNumber = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val method = parts.getOrNull(1)?.let { rawMethod ->
            SipMethod.values().firstOrNull { it.name.equals(rawMethod, ignoreCase = true) }
        } ?: return null
        return ResponseTransactionKey(callId, cseqNumber, method)
    }
}
