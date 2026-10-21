//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog

class PrackWaitTracker {
    private val lock = Object()
    private val pending = mutableSetOf<Int>()

    /**
     * Whether the peer PRACKed any reliable provisional response we sent in the
     * current dialog. Offer/answer is only complete once that has happened, so
     * the final 200 OK may omit the SDP answer only if this is true.
     */
    private var acknowledgedAny = false

    fun add(id: Int) {
        synchronized(lock) {
            pending += id
        }
    }

    fun ack(id: Int) {
        synchronized(lock) {
            pending -= id
            acknowledgedAny = true
            lock.notifyAll()
        }
    }

    fun anyAcknowledged(): Boolean = synchronized(lock) { acknowledgedAny }

    fun waitFor(id: Int) {
        synchronized(lock) {
            while (pending.contains(id)) {
                lock.wait(1000)
            }
        }
    }

    fun clearAndNotifyAll() {
        synchronized(lock) {
            pending.clear()
            // Reset per dialog: a PRACK from a previous call must not make a
            // later 200 OK omit its answer. dropStaleBeforeAccept() deliberately
            // does not reset it, since by then the PRACK may legitimately have
            // arrived for this dialog.
            acknowledgedAny = false
            lock.notifyAll()
        }
    }

    fun dropStaleBeforeAccept(logTag: String) {
        synchronized(lock) {
            if (pending.isNotEmpty()) {
                Rlog.w(logTag, "Dropping stale PRACK waits before accept: $pending")
                pending.clear()
                lock.notifyAll()
            }
        }
    }
}
