package com.lerchenflo.schneaggchatv3server.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.bson.types.ObjectId
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

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
 * Every line names its user in the same place:
 *   `18.10.2026 14:03:22.123 WARN    flo                       | message`
 *
 * - The user is the one who made the current HTTP request, found automatically. Pass `user` where
 *   there is no request (coroutines, socket handler, schedulers) or the line is about someone else.
 * - A second user inside the message: `"Friend request to ${AppLogger.nameOf(friendId)}"`.
 *
 * A call only puts the line into a queue. One background worker takes the lines out in order, looks
 * up the usernames and prints them, so a request never waits for a username lookup.
 *
 * Every line is also queued for the admin monitoring dashboard - `AppLogService` drains [pending]
 * into the `app_logs` collection. The queue lives here (not in the Spring bean) so lines logged
 * during startup, before the bean exists, are kept too.
 *
 * `tag` is a stable call-site id (e.g. `join_event_no_access`) the dashboard groups and searches by.
 * WARN/ERROR lines should carry one; untagged lines with an `error` are grouped by an automatic
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

    private val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS")

    // ANSI color codes
    private const val RESET   = "\u001B[0m"
    private const val RED     = "\u001B[31m"
    private const val GREEN   = "\u001B[32m"
    private const val YELLOW  = "\u001B[33m"
    private const val CYAN    = "\u001B[36m"
    private const val MAGENTA = "\u001B[35m"
    private const val GRAY    = "\u001B[90m"

    /** Width of the user column: the longest username (25) and the id fallback (24) both fit. */
    private const val USER_WIDTH = 25
    private const val NAME_CACHE_MS = 10 * 60 * 1000L
    /** After a failed lookup (database down) lines show the id only for this long, so the queue keeps moving. */
    private const val LOOKUP_PAUSE_MS = 30 * 1000L

    /** Upper bound so a log storm while Mongo is down can't eat the heap - overflow is dropped and counted. */
    private const val MAX_PENDING = 20_000

    val pending = ConcurrentLinkedQueue<PendingAppLog>()
    private val pendingSize = AtomicInteger(0)
    val dropped = AtomicLong(0)

    private class Line(
        val time: LocalDateTime,
        val color: String,
        val level: String,
        val user: ObjectId?,
        val tag: String?,
        val message: String,
        val error: Throwable?,
    )

    /** [name] is null when no user has this id (deleted account). */
    private class CachedName(val name: String?, val fetchedAt: Long)

    /** Set once at startup by `UserLookupService`. Until then lines show the id instead of the name. */
    @Volatile
    var usernameLookup: ((ObjectId) -> String?)? = null

    private val queue = Channel<Line>(capacity = 10_000)

    // Only the worker reads and writes these, so they need no locking
    private val names = object : LinkedHashMap<ObjectId, CachedName>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ObjectId, CachedName>?) = size > 5_000
    }
    private var lookupPausedUntil = 0L

    private val mention = Regex("""\{user:([0-9a-f]{24})}""")

    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        for (line in queue) {
            try {
                println(format(line, resolveNames = true))
            } catch (e: Exception) {
                println(format(line, resolveNames = false))
            }
        }
    }

    init {
        // Print what is still queued when the server stops
        Runtime.getRuntime().addShutdownHook(Thread {
            queue.close()
            runBlocking { withTimeoutOrNull(2_000.milliseconds) { worker.join() } }
        })
    }

    private fun log(color: String, label: String, level: AppLogLevel, message: String, user: ObjectId?, tag: String?, error: Throwable?) {
        val line = Line(LocalDateTime.now(), color, label, user ?: requestUser(), tag, message, error)
        // Queue full or closed: print right away, without looking up names
        if (queue.trySend(line).isFailure) println(format(line, resolveNames = false))
        record(level, message, tag, error, user)
    }

    /**
     * Queues a line for storage without printing it. Used directly by the Logback bridge, whose
     * events Logback already printed itself.
     */
    fun record(level: AppLogLevel, message: String, tag: String?, throwable: Throwable?, user: ObjectId? = null) {
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
                userId = user?.toHexString() ?: context?.userId ?: RequestLogContext.authenticatedUserId(),
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

    /**
     * The user of the current HTTP request. Has to be read here: the worker runs on another thread.
     * The request context keeps it after Spring Security cleared its own context at the end of the chain.
     */
    private fun requestUser(): ObjectId? =
        (RequestLogContext.current()?.userId ?: RequestLogContext.authenticatedUserId())
            ?.takeIf { ObjectId.isValid(it) }
            ?.let { ObjectId(it) }

    private fun format(line: Line, resolveNames: Boolean): String {
        val ts = "${GRAY}${line.time.format(formatter)}${RESET}"
        val lvl = "${line.color}${line.level.padEnd(7)}${RESET}"
        val user = line.user?.let { describe(it, resolveNames) } ?: "-"
        val tagPart = if (line.tag != null) "${GRAY}[${line.tag}]${RESET} " else ""
        val message = replaceMentions(line.message) { describe(it, resolveNames) }
        val text = "$ts $lvl ${user.padEnd(USER_WIDTH)} | $tagPart$message"
        return if (line.error != null) "$text\n${line.error.stackTraceToString().trimEnd()}" else text
    }

    /** The username; the id only when the name is unknown (deleted account, database down, before startup). */
    private fun describe(userId: ObjectId, resolveNames: Boolean): String {
        val name = if (resolveNames) lookupName(userId) else null
        return name ?: userId.toHexString()
    }

    private fun lookupName(userId: ObjectId): String? {
        val now = System.currentTimeMillis()
        names[userId]?.takeIf { now - it.fetchedAt < NAME_CACHE_MS }?.let { return it.name }

        val lookup = usernameLookup ?: return null
        if (now < lookupPausedUntil) return null
        return try {
            val name = lookup(userId)
            names[userId] = CachedName(name, now)
            name
        } catch (e: Exception) {
            lookupPausedUntil = now + LOOKUP_PAUSE_MS
            null
        }
    }

    /** Marks a second user inside a message; printed as the username. */
    fun nameOf(userId: ObjectId): String = "{user:${userId.toHexString()}}"

    /** The users marked with [nameOf] in [message]. */
    fun mentionedUsers(message: String): List<ObjectId> =
        mention.findAll(message).map { ObjectId(it.groupValues[1]) }.toList()

    /** [message] with every [nameOf] mark replaced by [name] of that user. */
    fun replaceMentions(message: String, name: (ObjectId) -> String): String =
        mention.replace(message) { name(ObjectId(it.groupValues[1])) }

    /** General information */
    fun info(message: String, user: ObjectId? = null, tag: String? = null) =
        log(CYAN, "INFO", AppLogLevel.INFO, message, user, tag, null)

    /** Successful operations - stored as INFO */
    fun success(message: String, user: ObjectId? = null, tag: String? = null) =
        log(GREEN, "SUCCESS", AppLogLevel.INFO, message, user, tag, null)

    /** Warnings — non-critical issues */
    fun warn(message: String, user: ObjectId? = null, tag: String? = null, error: Throwable? = null) =
        log(YELLOW, "WARN", AppLogLevel.WARN, message, user, tag, error)

    /** Errors — failures that need attention. Pass `error` to print the stack trace. */
    fun error(message: String, user: ObjectId? = null, tag: String? = null, error: Throwable? = null) =
        log(RED, "ERROR", AppLogLevel.ERROR, message, user, tag, error)

    /** Debug — verbose info for development */
    fun debug(message: String, user: ObjectId? = null, tag: String? = null) =
        log(MAGENTA, "DEBUG", AppLogLevel.DEBUG, message, user, tag, null)

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
