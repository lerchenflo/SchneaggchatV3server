@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user.recap

import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.user.recap.model.ChatHabitsRecap
import com.lerchenflo.schneaggchatv3server.user.recap.model.ConversationStarterRow
import com.lerchenflo.schneaggchatv3server.user.recap.model.MonologueRecap
import com.lerchenflo.schneaggchatv3server.user.recap.model.ReaderSpeed
import com.lerchenflo.schneaggchatv3server.user.recap.model.TextCount
import org.bson.types.ObjectId
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

/** Silence after which the next message starts a new conversation. */
internal val CONVERSATION_GAP = 6.hours
private const val STARTER_PARTNER_ROWS = 5
private const val FASTEST_READER_MIN_SAMPLES = 10
private const val MAX_OPENER_LENGTH = 40

/**
 * Chat habits of [requesterId] in the recap year: read and reply speed, conversation starts,
 * openers and the longest monologue in direct chats, plus the emoji typed in all chats.
 *
 * [messages] are the requester's messages without SYSTEM ones (as the rest of the recap uses them),
 * all time: a conversation or an unanswered run that began before the year still decides what
 * counts inside it.
 */
internal fun buildChatHabitsRecap(
    messages: List<Message>,
    requesterId: ObjectId,
    yearStart: Long,
    yearEnd: Long,
    nameFor: (ObjectId) -> String,
): ChatHabitsRecap {
    fun Message.inYear() = sendDate.epochSeconds in yearStart until yearEnd

    val readMillis = mutableListOf<Long>()
    val replyMillis = mutableListOf<Long>()
    val partnerReadMillis = mutableMapOf<ObjectId, MutableList<Long>>()
    val conversationsByPartner = mutableMapOf<ObjectId, Long>()
    val startedByPartner = mutableMapOf<ObjectId, Long>()
    val openers = mutableMapOf<String, Long>()
    var longestMonologue: MonologueRecap? = null

    val directChats = messages
        .filter { !it.groupMessage && it.senderId != it.receiverId }
        .groupBy { if (it.senderId == requesterId) it.receiverId else it.senderId }

    for ((partnerId, chat) in directChats) {
        var previous: Message? = null
        var unansweredSince: Message? = null
        var runLength = 0
        var runStart: Message? = null

        for (message in chat.sortedWith(compareBy({ it.sendDate }, { it.id }))) {
            val fromMe = message.senderId == requesterId

            val startsConversation = previous == null || message.sendDate - previous.sendDate >= CONVERSATION_GAP
            if (startsConversation && message.inYear()) {
                conversationsByPartner.merge(partnerId, 1, Long::plus)
                if (fromMe) {
                    startedByPartner.merge(partnerId, 1, Long::plus)
                    if (message.msgType == MessageType.TEXT) {
                        normalizeOpener(message.content)?.let { openers.merge(it, 1, Long::plus) }
                    }
                }
            }

            if (fromMe) {
                val waitingSince = unansweredSince
                if (waitingSince != null && message.inYear()) {
                    replyMillis += (message.sendDate - waitingSince.sendDate).inWholeMilliseconds.coerceAtLeast(0)
                }
                unansweredSince = null

                if (runLength == 0) runStart = message
                runLength++
                val start = runStart!!
                if (start.inYear() && runLength > (longestMonologue?.messageCount ?: 0)) {
                    longestMonologue = MonologueRecap(
                        messageCount = runLength,
                        partnerId = partnerId.toHexString(),
                        partnerName = nameFor(partnerId),
                        startedAt = start.sendDate.toEpochMilliseconds(),
                    )
                }

                if (message.inYear()) {
                    message.readers.firstOrNull { it.userId == partnerId }?.let { reader ->
                        partnerReadMillis.getOrPut(partnerId) { mutableListOf() } +=
                            (reader.readAt - message.sendDate).inWholeMilliseconds.coerceAtLeast(0)
                    }
                }
            } else {
                if (unansweredSince == null) unansweredSince = message
                runLength = 0

                if (message.inYear()) {
                    message.readers.firstOrNull { it.userId == requesterId }?.let { reader ->
                        readMillis += (reader.readAt - message.sendDate).inWholeMilliseconds.coerceAtLeast(0)
                    }
                }
            }
            previous = message
        }
    }

    val fastestReader = partnerReadMillis
        .filterValues { it.size >= FASTEST_READER_MIN_SAMPLES }
        .map { (partnerId, samples) -> partnerId to samples }
        .minByOrNull { (_, samples) -> median(samples)!! }
        ?.let { (partnerId, samples) ->
            ReaderSpeed(
                id = partnerId.toHexString(),
                name = nameFor(partnerId),
                medianMillis = median(samples)!!,
                sampleCount = samples.size.toLong(),
            )
        }

    val starterRows = conversationsByPartner.entries
        .sortedWith(compareByDescending<Map.Entry<ObjectId, Long>> { it.value }.thenBy { it.key })
        .take(STARTER_PARTNER_ROWS)
        .map { (partnerId, conversations) ->
            ConversationStarterRow(
                id = partnerId.toHexString(),
                name = nameFor(partnerId),
                conversations = conversations,
                startedByMe = startedByPartner[partnerId] ?: 0,
            )
        }

    val topOpener = openers.entries
        .filter { it.value >= 2 }
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .firstOrNull()
        ?.let { TextCount(it.key, it.value) }

    val ownTexts = messages
        .filter { it.senderId == requesterId && it.msgType == MessageType.TEXT && it.inYear() && it.content.isNotBlank() }
        .map { it.content }

    return ChatHabitsRecap(
        medianReadMillis = median(readMillis),
        readSampleCount = readMillis.size.toLong(),
        fastestReader = fastestReader,
        medianReplyMillis = median(replyMillis),
        replySampleCount = replyMillis.size.toLong(),
        conversations = conversationsByPartner.values.sum(),
        conversationsStartedByMe = startedByPartner.values.sum(),
        starterPerPartner = starterRows,
        topOpener = topOpener,
        longestMonologue = longestMonologue,
        topEmojiTyped = RecapTextStats.topEmoji(ownTexts),
    )
}

/** Lower case, whitespace collapsed, trailing punctuation removed; null when empty or too long to be an opener. */
internal fun normalizeOpener(content: String): String? {
    val text = content.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ',', ' ')
    return text.takeIf { it.isNotEmpty() && it.length <= MAX_OPENER_LENGTH }
}

/** Median of [values], the mean of the two middle values for an even count; null when empty. */
internal fun median(values: List<Long>): Long? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}
