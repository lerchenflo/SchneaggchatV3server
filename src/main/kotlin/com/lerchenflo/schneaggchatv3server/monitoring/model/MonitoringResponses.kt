package com.lerchenflo.schneaggchatv3server.monitoring.model

import com.lerchenflo.schneaggchatv3server.util.AppLogLevel

/**
 * Time ranges selectable on the dashboard. [bucketMs] is the width of one chart column, picked so
 * every range draws roughly 50-100 columns.
 */
enum class MonitoringRange(val durationMs: Long, val bucketMs: Long) {
    HOUR_1(HOUR, 60_000),
    HOURS_12(12 * HOUR, 10 * 60_000),
    HOURS_24(24 * HOUR, 20 * 60_000),
    DAYS_3(72 * HOUR, HOUR),
    WEEK_1(7 * 24 * HOUR, 2 * HOUR),
    MONTH_1(30 * 24 * HOUR, 12 * HOUR),
}

private const val HOUR = 60 * 60_000L

/** Shared time axis of every series in one response: column `i` covers `[start + i*bucketMs, +bucketMs)`. */
data class TimeAxis(
    val start: Long,
    val bucketMs: Long,
    val buckets: Int,
)

data class LevelCounts(
    val errors: Long,
    val warnings: Long,
    val failedRequests: Long,
    val serverErrors: Long,
    val affectedUsers: Long,
)

data class StatusCount(val status: Int, val count: Long)

data class EndpointFailure(
    val method: String?,
    val endpoint: String,
    val count: Long,
    val lastSeen: Long,
    val statuses: List<StatusCount>,
)

data class MonitoringOverview(
    val axis: TimeAxis,
    val current: LevelCounts,
    /** Same counts for the equally long period right before, for the trend arrows. */
    val previous: LevelCounts,
    val errorSeries: List<Long>,
    val warningSeries: List<Long>,
    val clientErrorSeries: List<Long>,
    val serverErrorSeries: List<Long>,
    val statusCodes: List<StatusCount>,
    val topEndpoints: List<EndpointFailure>,
    val latestMetrics: ServerMetricsSnapshotResponse?,
)

/** One row of the "errors by type" table, with its own column chart. */
data class ErrorTypeSummary(
    val tag: String,
    /** ERROR if any occurrence was an error, else WARN. */
    val level: AppLogLevel,
    val count: Long,
    val firstSeen: Long,
    val lastSeen: Long,
    val affectedUsers: Long,
    val lastMessage: String,
    val series: List<Long>,
)

data class ErrorTypesResponse(
    val axis: TimeAxis,
    val types: List<ErrorTypeSummary>,
)

data class AppLogEntryResponse(
    val id: String,
    val timestamp: Long,
    val kind: AppLogKind,
    val level: AppLogLevel,
    val tag: String?,
    val message: String,
    val userId: String?,
    val username: String?,
    val requestId: String?,
    val method: String?,
    val path: String?,
    val status: Int?,
    val durationMs: Long?,
    val exceptionClass: String?,
    val stackTrace: String?,
)

data class AppLogPage(
    val entries: List<AppLogEntryResponse>,
    val moreEntries: Boolean,
)

data class ServerMetricsSnapshotResponse(
    val timestamp: Long,
    val heapUsedMb: Double,
    val heapMaxMb: Double,
    val nonHeapUsedMb: Double,
    val processCpuPercent: Double,
    val systemCpuPercent: Double,
    val threadCount: Int,
    val uptimeSeconds: Long,
    val socketConnections: Int,
    val socketUsers: Int,
    val activeDevices: Map<String, Long>,
    val mongoPingMs: Double,
    val redisPingMs: Double,
)

/**
 * Status charts. Gauges are averaged per column, counters summed per column; columns without a
 * snapshot (server was down) are null so the chart shows a gap instead of a fake zero.
 */
data class MetricsSeriesResponse(
    val axis: TimeAxis,
    val gauges: Map<String, List<Double?>>,
    val counters: Map<String, List<Long?>>,
    val activeDevices: Map<String, List<Long?>>,
)
