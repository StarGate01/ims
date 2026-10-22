//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

/**
 * Whether a run of failed RTP transmissions should be treated as the uplink being lost.
 *
 * A single failed datagram is normal and must not disturb the media path: the caller keeps
 * draining the encoder, because stopping would leave encoded output buffered and the codec can
 * then refuse further input. Only a sustained run means the socket or the bearer is gone.
 */
internal object SipUplinkSendFailurePolicy {
    /**
     * Roughly a second of 20 ms frames. Long enough that a transient error is ridden out, short
     * enough that a dead bearer is noticed well inside a user's patience.
     */
    const val CONSECUTIVE_FAILURES_BEFORE_GIVING_UP = 50

    fun uplinkIsLost(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= CONSECUTIVE_FAILURES_BEFORE_GIVING_UP

    /** True when this failure should be logged, keeping a broken socket from flooding the log. */
    fun shouldLog(consecutiveFailures: Int): Boolean =
        consecutiveFailures <= 3 || uplinkIsLost(consecutiveFailures)
}
