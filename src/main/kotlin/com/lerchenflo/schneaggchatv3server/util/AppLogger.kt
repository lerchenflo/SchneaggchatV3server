package com.lerchenflo.schneaggchatv3server.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.bson.types.ObjectId
import org.springframework.security.core.context.SecurityContextHolder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

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

    private class Line(
        val time: LocalDateTime,
        val color: String,
        val level: String,
        val user: ObjectId?,
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

    private fun log(color: String, level: String, message: String, user: ObjectId?, error: Throwable? = null) {
        val line = Line(LocalDateTime.now(), color, level, user ?: requestUser(), message, error)
        // Queue full or closed: print right away, without looking up names
        if (queue.trySend(line).isFailure) println(format(line, resolveNames = false))
    }

    /** The user of the current HTTP request. Has to be read here: the worker runs on another thread. */
    private fun requestUser(): ObjectId? =
        (SecurityContextHolder.getContext().authentication?.principal as? String)
            ?.takeIf { ObjectId.isValid(it) }
            ?.let { ObjectId(it) }

    private fun format(line: Line, resolveNames: Boolean): String {
        val ts = "${GRAY}${line.time.format(formatter)}${RESET}"
        val lvl = "${line.color}${line.level.padEnd(7)}${RESET}"
        val user = line.user?.let { describe(it, resolveNames) } ?: "-"
        val message = mention.replace(line.message) { describe(ObjectId(it.groupValues[1]), resolveNames) }
        val text = "$ts $lvl ${user.padEnd(USER_WIDTH)} | $message"
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

    /** General information */
    fun info(message: String, user: ObjectId? = null) = log(CYAN, "INFO", message, user)

    /** Successful operations */
    fun success(message: String, user: ObjectId? = null) = log(GREEN, "SUCCESS", message, user)

    /** Warnings — non-critical issues */
    fun warn(message: String, user: ObjectId? = null, error: Throwable? = null) = log(YELLOW, "WARN", message, user, error)

    /** Errors — failures that need attention. Pass `error` to print the stack trace. */
    fun error(message: String, user: ObjectId? = null, error: Throwable? = null) = log(RED, "ERROR", message, user, error)

    /** Debug — verbose info for development */
    fun debug(message: String, user: ObjectId? = null) = log(MAGENTA, "DEBUG", message, user)
}
