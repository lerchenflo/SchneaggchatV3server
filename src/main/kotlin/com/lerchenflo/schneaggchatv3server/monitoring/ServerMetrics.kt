package com.lerchenflo.schneaggchatv3server.monitoring

import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory event counters, read and reset once a minute by [ServerMetricsService] into a
 * `server_metrics` snapshot. A plain object so the sending services can bump them without an
 * extra injected dependency.
 */
object ServerMetrics {
    val fcmSent = AtomicLong()
    val fcmFailed = AtomicLong()
    val apnsSent = AtomicLong()
    val apnsFailed = AtomicLong()
    val emailsSent = AtomicLong()
    val emailsFailed = AtomicLong()
    val logins = AtomicLong()

    // Socket or push decision, see DeliveryService
    val pushesImmediate = AtomicLong()
    val pushesHeld = AtomicLong()
    val pushesAcked = AtomicLong()
    val pushesAckTimedOut = AtomicLong()
    val pushesSessionClosed = AtomicLong()
    val ackLatencyTotalMs = AtomicLong()

    /** Pushes to tokens not linked to a device. At 0 the legacy token collections can go. */
    val legacyTokenPushes = AtomicLong()
}
