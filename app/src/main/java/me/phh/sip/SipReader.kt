//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream

internal const val SIP_MAX_HEADER_LINE_BYTES = 8 * 1024

internal class SipParseException(message: String) : IOException(message)

/**
 * @param streamTransport true for TCP/TLS, where RFC 3261 section 20.14 makes Content-Length
 *   mandatory because the stream carries no other message boundary.
 */
fun InputStream.sipReader(streamTransport: Boolean = false): SipReader =
    SipReader(this, streamTransport)

/**
 * Byte-oriented SIP reader.
 *
 * SIP bodies may contain arbitrary binary data, so character readers cannot
 * safely frame a message. A one-byte pushback buffer is enough to inspect the
 * first byte of the next physical line while unfolding legacy header lines.
 */
class SipReader(
    input: InputStream,
    /** See [sipReader]: affects diagnostics for a message with no Content-Length. */
    val streamTransport: Boolean = false,
) : InputStream() {
    private val input = PushbackInputStream(BufferedInputStream(input), 1)

    override fun read(): Int = input.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        input.read(buffer, offset, length)

    override fun close() = input.close()

    private fun readPhysicalLine(): ByteArray? {
        val line = ByteArrayOutputStream()
        while (true) {
            when (val value = input.read()) {
                -1 -> {
                    if (line.size() == 0) return null
                    throw SipParseException("SIP line ended before CRLF")
                }
                '\n'.code -> break
                else -> {
                    if (line.size() >= SIP_MAX_HEADER_LINE_BYTES) {
                        throw SipParseException(
                            "SIP line exceeds $SIP_MAX_HEADER_LINE_BYTES bytes",
                        )
                    }
                    line.write(value)
                }
            }
        }

        val bytes = line.toByteArray()
        return if (bytes.lastOrNull() == '\r'.code.toByte()) {
            bytes.copyOf(bytes.size - 1)
        } else {
            bytes
        }
    }

    /**
     * Reads and unfolds one SIP header/start line. A zero-length physical line
     * terminates the header section and is represented as null for the
     * existing lineSequence parser contract.
     */
    /**
     * Whether any byte remains, without consuming it.
     *
     * Lets a caller tell an empty line apart from end of stream: [parseMessage] returns null for
     * both, but only the latter means the connection is finished. RFC 3261 section 7.5 allows
     * stray CRLFs between messages and an RFC 5626 keepalive "pong" is exactly a bare CRLF, so
     * treating every null as end of stream closes a healthy connection.
     */
    fun hasMoreInput(): Boolean {
        val c = input.read()
        if (c == -1) return false
        input.unread(c)
        return true
    }

    fun readLine(): String? {
        val first = readPhysicalLine() ?: return null
        if (first.isEmpty()) return null

        val unfolded = ByteArrayOutputStream()
        unfolded.write(first)

        while (true) {
            var next = input.read()
            if (next == -1) break
            if (next != ' '.code && next != '\t'.code) {
                input.unread(next)
                break
            }

            do {
                next = input.read()
            } while (next == ' '.code || next == '\t'.code)

            if (next == -1) {
                throw SipParseException("SIP continuation ended before CRLF")
            }
            input.unread(next)
            val continuation = readPhysicalLine()
                ?: throw SipParseException("SIP continuation ended before CRLF")

            if (unfolded.size() + 1 + continuation.size > SIP_MAX_HEADER_LINE_BYTES) {
                throw SipParseException(
                    "Unfolded SIP line exceeds $SIP_MAX_HEADER_LINE_BYTES bytes",
                )
            }
            unfolded.write(' '.code)
            unfolded.write(continuation)
        }

        return unfolded.toString(Charsets.US_ASCII.name())
    }

    fun readNBytes2(length: Int): ByteArray {
        if (length < 0) throw SipParseException("Negative SIP body length: $length")

        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(bytes, offset, length - offset)
            if (read < 0) {
                throw SipParseException(
                    "SIP body ended early: expected=$length received=$offset",
                )
            }
            offset += read
        }
        return bytes
    }
}

// lineSequence copied from kotlin sources and adapted to SipReader.
fun SipReader.lineSequence(): Sequence<String> = LinesSequence(this).constrainOnce()

private class LinesSequence(private val reader: SipReader) : Sequence<String> {
    override fun iterator(): Iterator<String> = object : Iterator<String> {
        private var nextValue: String? = null
        private var done = false

        override fun hasNext(): Boolean {
            if (nextValue == null && !done) {
                nextValue = reader.readLine()
                if (nextValue == null) done = true
            }
            return nextValue != null
        }

        override fun next(): String {
            if (!hasNext()) throw NoSuchElementException()
            return nextValue!!.also { nextValue = null }
        }
    }
}
