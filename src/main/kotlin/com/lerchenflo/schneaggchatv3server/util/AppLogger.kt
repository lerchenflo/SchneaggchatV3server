package com.lerchenflo.schneaggchatv3server.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class AppLogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * One log line waiting to be stored. Request context (user, request id, path) is captured on the
 * logging thread, because the queue is drained later on a scheduler thread that has none.
 */
data class PendingAppLog(
    val timestampMillis: Long,
    val level: AppLogLevel,
    val message: String,
    val tag: String?,
    val throwable: Throwable?,
    val requestId: String?,
    val userId: String?,
    val method: String?,
    val path: String?,
    /** Never stored, see [RequestLogContext.clientIp]. */
    val clientIp: String?,
)

/**
 * Lightweight console logger with colored, timestamped output.
 * Use this object anywhere instead of println().
 *
 * Every line is also queued for the admin monitoring dashboard - `AppLogService` drains [pending]
 * into the `app_logs` collection. The queue lives here (not in the Spring bean) so lines logged
 * during startup, before the bean exists, are kept too.
 *
 * [tag] is a stable call-site id (e.g. `join_event_no_access`) the dashboard groups and searches by.
 * WARN/ERROR lines should carry one; untagged lines with a [throwable] are grouped by an automatic
 * tag derived from the exception (see [autoTag]).
 *
 * Colors:
 *   INFO    → Cyan
 *   SUCCESS → Green
 *   WARN    → Yellow
 *   ERROR   → Red
 *   DEBUG   → Magenta
 */
object AppLogger {

    private val formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss.SSS")

    // ANSI color codes
    private const val RESET   = "\u001B[0m"
    private const val RED     = "\u001B[31m"
    private const val GREEN   = "\u001B[32m"
    private const val YELLOW  = "\u001B[33m"
    private const val CYAN    = "\u001B[36m"
    private const val MAGENTA = "\u001B[35m"
    private const val GRAY    = "\u001B[90m"

    /** Upper bound so a log storm while Mongo is down can't eat the heap - overflow is dropped and counted. */
    private const val MAX_PENDING = 20_000

    val pending = ConcurrentLinkedQueue<PendingAppLog>()
    private val pendingSize = AtomicInteger(0)
    val dropped = AtomicLong(0)

    private fun timestamp(): String = LocalDateTime.now().format(formatter)

    private fun log(color: String, label: String, level: AppLogLevel, message: String, tag: String?, throwable: Throwable?) {
        val ts = "${GRAY}${timestamp()}${RESET}"
        val lvl = "${color}${label.padEnd(7)}${RESET}"
        val tagPart = if (tag != null) "${GRAY}[$tag]${RESET} " else ""
        println("$ts $lvl $tagPart$message")
        throwable?.printStackTrace()
        record(level, message, tag, throwable)
    }

    /**
     * Queues a line for storage without printing it. Used directly by the Logback bridge, whose
     * events Logback already printed itself.
     */
    fun record(level: AppLogLevel, message: String, tag: String?, throwable: Throwable?) {
        if (pendingSize.incrementAndGet() > MAX_PENDING) {
            pendingSize.decrementAndGet()
            dropped.incrementAndGet()
            return
        }
        val context = RequestLogContext.current()
        pending.add(
            PendingAppLog(
                timestampMillis = System.currentTimeMillis(),
                level = level,
                message = message,
                tag = tag,
                throwable = throwable,
                requestId = context?.requestId,
                userId = context?.userId ?: RequestLogContext.authenticatedUserId(),
                method = context?.method,
                path = context?.path,
                clientIp = context?.clientIp,
            )
        )
    }

    /** Takes up to [max] queued lines, oldest first. */
    fun drain(max: Int): List<PendingAppLog> {
        val batch = ArrayList<PendingAppLog>(minOf(max, pendingSize.get().coerceAtLeast(0)))
        while (batch.size < max) {
            val next = pending.poll() ?: break
            pendingSize.decrementAndGet()
            batch += next
        }
        return batch
    }

    /** General information */
    fun info(message: String, tag: String? = null) = log(CYAN, "INFO", AppLogLevel.INFO, message, tag, null)

    /** Successful operations - stored as INFO */
    fun success(message: String, tag: String? = null) = log(GREEN, "SUCCESS", AppLogLevel.INFO, message, tag, null)

    /** Warnings — non-critical issues */
    fun warn(message: String, tag: String? = null, throwable: Throwable? = null) =
        log(YELLOW, "WARN", AppLogLevel.WARN, message, tag, throwable)

    /** Errors — failures that need attention */
    fun error(message: String, tag: String? = null, throwable: Throwable? = null) =
        log(RED, "ERROR", AppLogLevel.ERROR, message, tag, throwable)

    /** Debug — verbose info for development */
    fun debug(message: String, tag: String? = null) = log(MAGENTA, "DEBUG", AppLogLevel.DEBUG, message, tag, null)

    /** Helpers whose frames say nothing about where a failure really came from. */
    private val skippedFrameClasses = setOf(
        "com.lerchenflo.schneaggchatv3server.core.security.RequireAuthKt",
        "com.lerchenflo.schneaggchatv3server.core.security.AdminGuard",
        "com.lerchenflo.schneaggchatv3server.util.ValidationUtils",
        "com.lerchenflo.schneaggchatv3server.util.OptimisticRetryKt",
        // Servlet filters wrap every request - a framework 404 would otherwise be blamed on them.
        "com.lerchenflo.schneaggchatv3server.core.security.JwtAuthFilter",
        "com.lerchenflo.schneaggchatv3server.core.security.ratelimit.RateLimitFilter",
        "com.lerchenflo.schneaggchatv3server.monitoring.RequestLogFilter",
    )

    /**
     * Tag for a failure nobody tagged by hand: exception class + the first app frame that threw it,
     * e.g. `IllegalArgumentException@UserService.changeUserProfile`. Groups a plain `require()` per
     * call site without touching every call. Falls back to the bare class name for framework-only traces.
     */
    fun autoTag(throwable: Throwable): String {
        val frame = throwable.stackTrace.firstOrNull { frame ->
            frame.className.startsWith("com.lerchenflo.") &&
                    frame.className.substringBefore('$') !in skippedFrameClasses
        }
        val name = throwable.javaClass.simpleName.ifEmpty { throwable.javaClass.name }
        return if (frame == null) {
            name
        } else {
            "$name@${frame.className.substringAfterLast('.').substringBefore('$')}.${frame.methodName.substringBefore('$')}"
        }
    }
}
