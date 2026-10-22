//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.net.IpSecManager
import android.telephony.Rlog
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

object SipOperationWatchdog {
    fun connectSipSocket(
        logTag: String,
        connection: SipConnection,
        remoteAddress: InetAddress,
        remotePort: Int,
        label: String,
        timeoutMs: Long = 10_000L,
    ) {
        val finished = AtomicBoolean(false)
        var failure: Throwable? = null
        val connectThread = thread(name = "PhhImsSocketConnect-$label", isDaemon = true) {
            try {
                Rlog.d(logTag, "$label SIP socket connect start remote=$remoteAddress:$remotePort")
                connection.connect(remotePort)
            } catch (t: Throwable) {
                failure = t
            } finally {
                finished.set(true)
            }
        }

        connectThread.join(timeoutMs)
        if (!finished.get()) {
            val reason = "$label SIP socket connect timed out after ${timeoutMs}ms to $remoteAddress:$remotePort"
            Rlog.w(logTag, reason)
            try {
                connection.close()
            } catch (t: Throwable) {
                Rlog.d(logTag, "close timed-out $label SIP socket failed", t)
            }
            connectThread.join(1000L)
            throw SocketTimeoutException(reason)
        }

        failure?.let { throw it }
        Rlog.d(logTag, "$label SIP socket connect completed remote=$remoteAddress:$remotePort")
    }

    fun allocateSecurityParameterIndex(
        logTag: String,
        ipSecManager: IpSecManager,
        label: String,
        address: InetAddress,
        requestedSpi: Int? = null,
        timeoutMs: Long = 10_000L,
    ): IpSecManager.SecurityParameterIndex {
        val finished = AtomicBoolean(false)
        var failure: Throwable? = null

        // A SecurityParameterIndex reserves an SPI in the kernel until it is closed, and the
        // allocation cannot be interrupted. Once the caller has timed out, whatever the worker
        // eventually produces must still be closed or the reservation is leaked with no owner;
        // repeated slow allocations would exhaust the SPI space.
        //
        // Ownership is handed over through this slot: both sides take with getAndSet(null), so
        // exactly one of them can ever receive a non-null SPI, whichever order they race in.
        val slot = AtomicReference<IpSecManager.SecurityParameterIndex?>(null)
        val timedOut = AtomicBoolean(false)

        val allocThread = thread(name = "PhhImsIpsecAllocate-$label", isDaemon = true) {
            try {
                Rlog.d(
                    logTag,
                    "$label allocation start address=$address" +
                        if (requestedSpi != null) " requestedSpi=$requestedSpi" else "",
                )
                val spi = if (requestedSpi != null) {
                    ipSecManager.allocateSecurityParameterIndex(address, requestedSpi)
                } else {
                    ipSecManager.allocateSecurityParameterIndex(address)
                }
                slot.set(spi)
                if (timedOut.get()) {
                    slot.getAndSet(null)?.let { abandonedSpi ->
                        Rlog.w(
                            logTag,
                            "$label allocation completed after the caller timed out; " +
                                "closing spi=${abandonedSpi.spi} so it is not left reserved",
                        )
                        closeQuietly(logTag, "$label abandoned SPI") { abandonedSpi.close() }
                    }
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                finished.set(true)
            }
        }

        allocThread.join(timeoutMs)
        if (!finished.get()) {
            val reason = "$label allocation timed out after ${timeoutMs}ms address=$address" +
                if (requestedSpi != null) " requestedSpi=$requestedSpi" else ""
            Rlog.w(logTag, reason)
            timedOut.set(true)
            slot.getAndSet(null)?.let { lateSpi ->
                Rlog.w(
                    logTag,
                    "$label allocation landed during timeout handling; closing spi=${lateSpi.spi}",
                )
                closeQuietly(logTag, "$label late SPI") { lateSpi.close() }
            }
            throw SocketTimeoutException(reason)
        }

        failure?.let { throw it }
        val result = slot.getAndSet(null)
            ?: throw SocketTimeoutException("$label allocation returned no SPI")
        Rlog.d(logTag, "$label allocation completed spi=${result.spi}")
        return result
    }

    private inline fun closeQuietly(logTag: String, what: String, close: () -> Unit) {
        try {
            close()
        } catch (t: Throwable) {
            Rlog.w(logTag, "Closing $what failed", t)
        }
    }
}
