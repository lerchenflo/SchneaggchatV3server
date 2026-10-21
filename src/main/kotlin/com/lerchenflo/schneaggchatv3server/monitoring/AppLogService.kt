@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.monitoring

import com.lerchenflo.schneaggchatv3server.monitoring.model.AppLog
import com.lerchenflo.schneaggchatv3server.monitoring.model.AppLogEntryResponse
import com.lerchenflo.schneaggchatv3server.monitoring.model.AppLogKind
import com.lerchenflo.schneaggchatv3server.repository.AppLogRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.AppLogLevel
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.PendingAppLog
import com.lerchenflo.schneaggchatv3server.util.RequestLogContext
import jakarta.annotation.PreDestroy
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Writes queued log lines ([AppLogger.pending]) and failed-request rows into `app_logs` in
 * batches, so logging never costs a DB round trip on the request thread.
 *
 * Storage is bounded, because failed requests are written for unauthenticated callers too (a bot
 * flood of 401/404/429 must not fill the disk):
 * - WARN rows from the same source (kind, tag, status, endpoint, user - or client IP when
 *   anonymous) are merged per minute: the first is stored, the repeats become one summary row with
 *   [AppLog.count] at the end of the minute, so dashboard totals stay exact.
 * - At most [MAX_ROWS_PER_MINUTE] rows are stored per minute; the rest is dropped and counted in
 *   [AppLogger.dropped] (shown as `logsDropped` in the server metrics).
 */
@Service
class AppLogService(
    private val mongoTemplate: MongoTemplate,
    private val appLogRepository: AppLogRepository,
    private val userLookupService: UserLookupService,
) {
    companion object {
        val RETENTION = 31.days
        private const val MAX_BATCH = 1_000
        private const val MAX_MESSAGE_LENGTH = 4_000
        private const val MAX_STACK_TRACE_LENGTH = 16_000
        private const val MAX_REQUEST_QUEUE = 10_000
        private const val MAX_ROWS_PER_MINUTE = 600
    }

    /** Failed-request rows with the client IP they came from (merge key only, never stored). */
    private val pendingRequests = ConcurrentLinkedQueue<Pair<AppLog, String?>>()

    // Current one-minute merge window. Only touched from flush(), which is synchronized.
    private var windowMinute = -1L
    private var windowStored = 0
    private val windowFirst = HashMap<String, AppLog>()
    private val windowRepeats = HashMap<String, Int>()

    /**
     * Records one failed (4xx/5xx) request. Called by [RequestLogFilter] after the chain finished;
     * the exception handler already put the failure's tag and reason into [context].
     */
    fun logFailedRequest(
        context: RequestLogContext,
        status: Int,
        endpoint: String,
        durationMs: Long,
        thrown: Throwable?,
    ) {
        if (pendingRequests.size >= MAX_REQUEST_QUEUE) {
            AppLogger.dropped.incrementAndGet()
            return
        }
        val reason = context.failureReason ?: thrown?.let { "${it.javaClass.simpleName}: ${it.message}" }
        pendingRequests.add(
            AppLog(
                timestamp = Clock.System.now(),
                kind = AppLogKind.REQUEST,
                level = if (status >= 500) AppLogLevel.ERROR else AppLogLevel.WARN,
                tag = context.failureTag ?: thrown?.let { AppLogger.autoTag(it) } ?: "http_$status",
                message = buildString {
                    append("$status ${context.method} ${context.path}")
                    if (reason != null) append(" - $reason")
                }.take(MAX_MESSAGE_LENGTH),
                userId = context.userId?.let(::ObjectId),
                requestId = context.requestId,
                method = context.method,
                path = context.path,
                endpoint = endpoint,
                status = status,
                durationMs = durationMs,
                exceptionClass = thrown?.javaClass?.name,
                stackTrace = thrown?.let(::stackTraceOf),
            ) to context.clientIp
        )
    }

    @Scheduled(fixedDelay = 1_000)
    @Synchronized
    fun flush() {
        while (true) {
            val drained = AppLogger.drain(MAX_BATCH).map { it.toAppLog() to it.clientIp } + drainRequests()
            val batch = admit(drained, System.currentTimeMillis() / 60_000)
            if (batch.isEmpty()) {
                if (drained.isEmpty()) return else continue
            }
            try {
                mongoTemplate.insert(batch, AppLog::class.java)
            } catch (e: Exception) {
                // Not AppLogger: a failing insert would queue another line about itself on every flush.
                System.err.println("AppLogService: could not store ${batch.size} log lines: ${e.message}")
                return
            }
            if (drained.size < MAX_BATCH) return
        }
    }

    /** Last chance to store what is still queued when the server shuts down. */
    @PreDestroy
    @Synchronized
    fun flushOnShutdown() {
        flush()
        val summaries = closeWindow()
        if (summaries.isNotEmpty()) {
            try {
                mongoTemplate.insert(summaries, AppLog::class.java)
            } catch (e: Exception) {
                System.err.println("AppLogService: could not store ${summaries.size} summary rows on shutdown: ${e.message}")
            }
        }
    }

    fun deleteExpired() {
        appLogRepository.deleteByTimestampBefore(Clock.System.now() - RETENTION)
    }

    /**
     * Converts rows for the admin panel, resolving all user ids of the batch in one lookup - the
     * row's user and the users marked in the message with [AppLogger.nameOf].
     */
    fun toResponses(logs: List<AppLog>): List<AppLogEntryResponse> {
        val userIds = logs.flatMap { listOfNotNull(it.userId) + AppLogger.mentionedUsers(it.message) }.distinct()
        val usernames = userLookupService
            .findAllById(userIds)
            .associate { it.id to it.username }

        return logs.map { log ->
            AppLogEntryResponse(
                id = log.id.toHexString(),
                timestamp = log.timestamp.toEpochMilliseconds(),
                kind = log.kind,
                level = log.level,
                tag = log.tag,
                message = AppLogger.replaceMentions(log.message) { usernames[it] ?: it.toHexString() },
                userId = log.userId?.toHexString(),
                username = log.userId?.let { usernames[it] },
                requestId = log.requestId,
                method = log.method,
                path = log.path,
                status = log.status,
                durationMs = log.durationMs,
                exceptionClass = log.exceptionClass,
                stackTrace = log.stackTrace,
            )
        }
    }

    /**
     * Applies the per-minute merge and cap to freshly drained rows. Returns what should be stored -
     * including the summary rows of the previous minute once it is over.
     */
    private fun admit(rows: List<Pair<AppLog, String?>>, minute: Long): List<AppLog> {
        val out = mutableListOf<AppLog>()
        if (minute != windowMinute) {
            out += closeWindow()
            windowMinute = minute
        }
        for ((row, clientIp) in rows) {
            // ERROR lines are never merged - each one may carry its own stack trace.
            val key = if (row.level == AppLogLevel.WARN) mergeKey(row, clientIp) else null
            if (key != null && key in windowFirst) {
                windowRepeats.merge(key, 1, Int::plus)
                continue
            }
            if (windowStored >= MAX_ROWS_PER_MINUTE) {
                AppLogger.dropped.incrementAndGet()
                continue
            }
            windowStored++
            if (key != null) windowFirst[key] = row
            out += row
        }
        return out
    }

    /** One summary row per merged source of the finished minute. Bounded by the cap, since only stored rows open a key. */
    private fun closeWindow(): List<AppLog> {
        val summaries = windowRepeats.mapNotNull { (key, repeats) ->
            val first = windowFirst[key] ?: return@mapNotNull null
            first.copy(
                id = ObjectId.get(),
                count = repeats,
                message = "[+$repeats gleiche in dieser Minute] ${first.message}".take(MAX_MESSAGE_LENGTH),
                stackTrace = null,
            )
        }
        windowFirst.clear()
        windowRepeats.clear()
        windowStored = 0
        return summaries
    }

    private fun mergeKey(row: AppLog, clientIp: String?): String =
        listOf(row.kind, row.tag, row.status, row.endpoint, row.userId ?: clientIp).joinToString("|")

    private fun drainRequests(): List<Pair<AppLog, String?>> {
        val batch = mutableListOf<Pair<AppLog, String?>>()
        while (batch.size < MAX_BATCH) {
            batch += pendingRequests.poll() ?: break
        }
        return batch
    }

    private fun PendingAppLog.toAppLog() = AppLog(
        timestamp = Instant.fromEpochMilliseconds(timestampMillis),
        kind = AppLogKind.LOG,
        level = level,
        tag = tag ?: throwable?.let { AppLogger.autoTag(it) },
        message = message.take(MAX_MESSAGE_LENGTH),
        userId = userId?.takeIf { ObjectId.isValid(it) }?.let(::ObjectId),
        requestId = requestId,
        method = method,
        path = path,
        exceptionClass = throwable?.javaClass?.name,
        stackTrace = throwable?.let(::stackTraceOf),
    )

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().take(MAX_STACK_TRACE_LENGTH)
    }
}
