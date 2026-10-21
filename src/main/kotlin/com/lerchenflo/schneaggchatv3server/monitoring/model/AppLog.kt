@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.monitoring.model

import com.lerchenflo.schneaggchatv3server.util.AppLogLevel
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class AppLogKind {
    /** A line written through `AppLogger` / the Logback bridge. */
    LOG,

    /** One failed (4xx/5xx) HTTP request, written by `RequestLogFilter`. */
    REQUEST,
}

/**
 * Technical log line for the admin monitoring dashboard. Separate from the business event log
 * (`logs` / `LogType`), which feeds stats and the recap. Rows older than
 * [com.lerchenflo.schneaggchatv3server.monitoring.AppLogService.RETENTION] are deleted by
 * `DatabaseCleanupTask`.
 *
 * Spring Data stores [timestamp] as an `{epochSeconds, nanosecondsOfSecond}` sub-document, so
 * indexes, range filters and the dashboard's bucketing all work on `timestamp.epochSeconds`.
 */
@TypeAlias("appLog")
@Document("app_logs")
@CompoundIndexes(
    CompoundIndex(name = "timestamp_idx", def = "{'timestamp.epochSeconds': -1}"),
    CompoundIndex(name = "kind_level_timestamp_idx", def = "{'kind': 1, 'level': 1, 'timestamp.epochSeconds': -1}"),
    CompoundIndex(name = "tag_timestamp_idx", def = "{'tag': 1, 'timestamp.epochSeconds': -1}"),
    CompoundIndex(name = "userid_timestamp_idx", def = "{'userId': 1, 'timestamp.epochSeconds': -1}"),
    CompoundIndex(name = "requestid_idx", def = "{'requestId': 1}"),
)
data class AppLog(
    @Id val id: ObjectId = ObjectId.get(),
    val timestamp: Instant,
    val kind: AppLogKind,
    val level: AppLogLevel,
    /** Call-site id (`join_event_no_access`) or automatic `Exception@Class.method` tag. */
    val tag: String?,
    val message: String,
    val userId: ObjectId?,
    val requestId: String?,
    val method: String?,
    val path: String?,
    /** Route pattern (`/groups/{id}`) for REQUEST rows, so failures group per endpoint. */
    val endpoint: String? = null,
    val status: Int? = null,
    val durationMs: Long? = null,
    val exceptionClass: String? = null,
    val stackTrace: String? = null,
    /**
     * How many identical occurrences this row stands for. > 1 on the summary row AppLogService
     * writes for repeats it merged within one minute; all dashboard counts sum this field.
     */
    val count: Int = 1,
)
