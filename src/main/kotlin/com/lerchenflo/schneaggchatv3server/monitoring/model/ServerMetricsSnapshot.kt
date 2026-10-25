@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.monitoring.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Server state sampled once a minute by `ServerMetricsService` for the dashboard's status charts.
 * Counter fields (`*Sent`, `*Failed`, `logins`, `messagesSent`, `newAccounts`, `gc*`) are deltas
 * over that minute; everything else is the value at sampling time.
 */
@TypeAlias("serverMetricsSnapshot")
@Document("server_metrics")
@CompoundIndex(name = "timestamp_idx", def = "{'timestamp.epochSeconds': 1}")
data class ServerMetricsSnapshot(
    @Id val id: ObjectId = ObjectId.get(),
    val timestamp: Instant,

    // JVM / host
    val heapUsedMb: Double,
    val heapMaxMb: Double,
    val nonHeapUsedMb: Double,
    /** 0..100, -1 when the JVM can't tell. */
    val processCpuPercent: Double,
    val systemCpuPercent: Double,
    val threadCount: Int,
    val gcCount: Long,
    val gcTimeMs: Long,
    val uptimeSeconds: Long,

    // Realtime + push
    val socketConnections: Int,
    val socketUsers: Int,
    val fcmSent: Long,
    val fcmFailed: Long,
    val apnsSent: Long,
    val apnsFailed: Long,
    val emailsSent: Long,
    val emailsFailed: Long,

    // App activity
    val messagesSent: Long,
    val newAccounts: Long,
    val logins: Long,
    /** Logged-in devices by device type name (incl. "UNKNOWN"). */
    val activeDevices: Map<String, Long>,
    /** Round trip of a ping, -1 when it failed. */
    val mongoPingMs: Double,
    val redisPingMs: Double,

    /** Log lines dropped because the write queue was full. */
    val logsDropped: Long,
)
