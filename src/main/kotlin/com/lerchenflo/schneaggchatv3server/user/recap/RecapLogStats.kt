@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user.recap

import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.message.messagemodel.SystemEventType
import com.lerchenflo.schneaggchatv3server.user.recap.model.NamedCount
import com.lerchenflo.schneaggchatv3server.user.recap.model.TextCount
import com.lerchenflo.schneaggchatv3server.user.recap.model.WakeRecap
import com.lerchenflo.schneaggchatv3server.util.Log
import org.bson.types.ObjectId
import kotlin.time.ExperimentalTime

// WakeService.logWake: "Woke user|group <id> on <n> device(s): <reason>"
private val WAKE_LOG = Regex("""^Woke (user|group) ([0-9a-f]{24}) on \d+ device\(s\): (.*)$""", RegexOption.DOT_MATCHES_ALL)

// GlobalExceptionHandler since 2026-09-04: "<ExceptionClass>: <message> | path=<uri>[ | ip=<ip>]"
private val EXCEPTION_CLASS = Regex("""^([A-Za-z_$][\w$]*): """)
private val EXCEPTION_PATH = Regex(""" \| path=(\S+)""")
private val OBJECT_ID_SEGMENT = Regex("""(?<=/)[0-9a-fA-F]{24}(?=/|$)""")

internal data class CrashDetails(val topException: TextCount?, val topEndpoint: TextCount?, val firstAt: Long?)

/** Most common exception class and endpoint of a user's EXCEPTION_THROWN logs, all time. */
internal fun crashDetails(exceptionLogs: List<Log>): CrashDetails {
    val newFormat = exceptionLogs.mapNotNull { it.message }.filter { EXCEPTION_PATH.containsMatchIn(it) }
    val classes = newFormat.mapNotNull { EXCEPTION_CLASS.find(it)?.groupValues?.get(1) }
    // The exception message can itself contain " | path=", the handler's own part is the last one.
    val paths = newFormat.mapNotNull { message ->
        EXCEPTION_PATH.findAll(message).lastOrNull()?.groupValues?.get(1)?.let { OBJECT_ID_SEGMENT.replace(it, "{id}") }
    }
    return CrashDetails(
        topException = mostCommon(classes),
        topEndpoint = mostCommon(paths),
        firstAt = exceptionLogs.minOfOrNull { it.timestamp }?.toEpochMilliseconds(),
    )
}

private fun mostCommon(values: List<String>): TextCount? = values
    .groupingBy { it }.eachCount()
    .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    .firstOrNull()
    ?.let { TextCount(it.key, it.value.toLong()) }

/**
 * Wakes of [requesterId]: sent ones from the WAKE_SENT logs, received ones from the WAKE_SENT system
 * messages (direct: to the requester; group: by others in the requester's groups).
 *
 * [rawMessages] must still contain the SYSTEM messages. [nameFor] resolves a user (group = false)
 * or group id to its name.
 */
internal fun buildWakeRecap(
    wakeLogs: List<Log>,
    rawMessages: List<Message>,
    requesterId: ObjectId,
    yearStart: Long,
    yearEnd: Long,
    nameFor: (id: ObjectId, group: Boolean) -> String,
): WakeRecap {
    data class SentWake(val targetId: ObjectId, val group: Boolean, val reason: String, val at: Long)

    val sent = wakeLogs.mapNotNull { log ->
        val match = log.message?.let { WAKE_LOG.matchEntire(it) } ?: return@mapNotNull null
        SentWake(
            targetId = ObjectId(match.groupValues[2]),
            group = match.groupValues[1] == "group",
            reason = match.groupValues[3].trim(),
            at = log.timestamp.epochSeconds,
        )
    }
    val sentThisYear = sent.filter { it.at in yearStart until yearEnd }

    val mostWoken = sentThisYear
        .groupBy { it.targetId to it.group }
        .maxByOrNull { (_, wakes) -> wakes.size }
        ?.let { (target, wakes) ->
            val (targetId, group) = target
            NamedCount(targetId.toHexString(), nameFor(targetId, group), group, wakes.size.toLong())
        }

    val topReason = sentThisYear
        .filter { it.reason.isNotBlank() }
        .groupBy { it.reason.lowercase() }
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, List<SentWake>>> { it.value.size }.thenByDescending { it.value.maxOf { wake -> wake.at } })
        .firstOrNull()
        ?.let { (_, wakes) -> TextCount(wakes.maxBy { it.at }.reason, wakes.size.toLong()) }

    val wakeMessages = rawMessages.filter {
        it.msgType == MessageType.SYSTEM && it.systemEvent?.eventType == SystemEventType.WAKE_SENT
    }
    val received = wakeMessages.filter { !it.groupMessage && it.receiverId == requesterId && it.senderId != requesterId }
    val receivedThisYear = received.filter { it.sendDate.epochSeconds in yearStart until yearEnd }
    val topWaker = receivedThisYear
        .groupBy { it.senderId }
        .maxByOrNull { (_, wakes) -> wakes.size }
        ?.let { (wakerId, wakes) -> NamedCount(wakerId.toHexString(), nameFor(wakerId, false), false, wakes.size.toLong()) }
    val groupWakesThisYear = wakeMessages.count {
        it.groupMessage && it.systemEvent?.actorId != requesterId && it.sendDate.epochSeconds in yearStart until yearEnd
    }

    return WakeRecap(
        // Counted from every log row, so a row whose message doesn't parse still counts as a wake.
        wakesSentThisYear = wakeLogs.count { it.timestamp.epochSeconds in yearStart until yearEnd }.toLong(),
        wakesSentAllTime = wakeLogs.size.toLong(),
        mostWoken = mostWoken,
        topReason = topReason,
        wakesReceivedThisYear = receivedThisYear.size.toLong(),
        wakesReceivedAllTime = received.size.toLong(),
        topWaker = topWaker,
        groupWakesThisYear = groupWakesThisYear.toLong(),
    )
}
