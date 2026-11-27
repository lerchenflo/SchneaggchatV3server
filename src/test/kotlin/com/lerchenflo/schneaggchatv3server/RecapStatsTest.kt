package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.message.messagemodel.Reader
import com.lerchenflo.schneaggchatv3server.message.messagemodel.SystemEvent
import com.lerchenflo.schneaggchatv3server.message.messagemodel.SystemEventType
import com.lerchenflo.schneaggchatv3server.user.recap.RecapTextStats
import com.lerchenflo.schneaggchatv3server.user.recap.buildChatHabitsRecap
import com.lerchenflo.schneaggchatv3server.user.recap.buildWakeRecap
import com.lerchenflo.schneaggchatv3server.user.recap.crashDetails
import com.lerchenflo.schneaggchatv3server.user.recap.median
import com.lerchenflo.schneaggchatv3server.user.recap.model.EmojiCount
import com.lerchenflo.schneaggchatv3server.user.recap.model.NamedCount
import com.lerchenflo.schneaggchatv3server.user.recap.model.TextCount
import com.lerchenflo.schneaggchatv3server.util.Log
import com.lerchenflo.schneaggchatv3server.util.LogType
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RecapStatsTest {

    private val me = ObjectId()
    private val friend = ObjectId()
    private val other = ObjectId()
    private val group = ObjectId()

    private val yearStartInstant = Instant.parse("2026-01-01T00:00:00Z")
    private val yearStart = yearStartInstant.epochSeconds
    private val yearEnd = Instant.parse("2027-01-01T00:00:00Z").epochSeconds
    private val t0 = Instant.parse("2026-03-01T10:00:00Z")

    private fun message(
        from: ObjectId,
        to: ObjectId,
        at: Instant,
        content: String = "x",
        readBy: ObjectId? = null,
        readAfter: Duration = Duration.ZERO,
        groupMessage: Boolean = false,
        type: MessageType = MessageType.TEXT,
        systemEvent: SystemEvent? = null,
    ) = Message(
        senderId = from,
        receiverId = to,
        groupMessage = groupMessage,
        msgType = type,
        answerId = null,
        content = content,
        systemEvent = systemEvent,
        sendDate = at,
        lastChanged = at,
        deleted = false,
        readers = listOfNotNull(readBy?.let { Reader(it, at + readAfter) }),
    )

    private fun names(id: ObjectId) = if (id == friend) "Friend" else "Other"

    @Test
    fun `conversations, openers, reply and read speed in direct chats`() {
        val messages = listOf(
            // Conversation 1, started by me
            message(me, friend, t0, "Hoi!", readBy = friend, readAfter = 30.seconds),
            message(friend, me, t0 + 5.minutes, "na", readBy = me, readAfter = 60.seconds),
            message(me, friend, t0 + 15.minutes, "jo"),
            // Conversation 2 (gap >= 6 h), started by me, three in a row
            message(me, friend, t0 + 8.hours, "hoi", readBy = friend, readAfter = 30.seconds),
            message(me, friend, t0 + 8.hours + 1.minutes, "bisch do?"),
            message(me, friend, t0 + 8.hours + 2.minutes, "?"),
            // Conversation 3, started by the friend, two messages before my reply
            message(friend, me, t0 + 20.hours, "morn?", readBy = me, readAfter = 120.seconds),
            message(friend, me, t0 + 20.hours + 1.minutes, "um 8"),
            message(me, friend, t0 + 20.hours + 4.minutes, "passt"),
            // Last year: ignored for every count, but still the previous message of the chat
            message(friend, other, yearStartInstant - 1.hours, "old").copy(senderId = other, receiverId = me),
            // Group message: no conversation stats
            message(me, group, t0, "hoi", groupMessage = true),
        )

        val recap = buildChatHabitsRecap(messages, me, yearStart, yearEnd, ::names)

        assertEquals(3L, recap.conversations)
        assertEquals(2L, recap.conversationsStartedByMe)
        assertEquals(TextCount("hoi", 2), recap.topOpener)
        // "jo", then 8 h later "hoi", "bisch do?", "?" without a reply in between: one run of 4
        assertEquals(4, recap.longestMonologue?.messageCount)
        assertEquals(friend.toHexString(), recap.longestMonologue?.partnerId)
        assertEquals((t0 + 15.minutes).toEpochMilliseconds(), recap.longestMonologue?.startedAt)
        // Replies: "na" -> "jo" after 10 min, "morn?" -> "passt" after 4 min
        assertEquals(7.minutes.inWholeMilliseconds, recap.medianReplyMillis)
        assertEquals(2L, recap.replySampleCount)
        // Read: 60 s and 120 s
        assertEquals(90_000L, recap.medianReadMillis)
        assertEquals(friend.toHexString(), recap.starterPerPartner.first().id)
        assertEquals(3L, recap.starterPerPartner.first().conversations)
        // The friend read only 2 of my messages, below the 10 needed for the fastest reader
        assertNull(recap.fastestReader)
    }

    @Test
    fun `emoji stay whole and are counted per occurrence`() {
        val result = RecapTextStats.topEmoji(listOf("so cool 😂😂", "cool 👍🏽", "Servus 🇦🇹 😂", "no emoji"))

        assertEquals(EmojiCount("😂", 3L), result.first())
        assertEquals(setOf("😂", "👍🏽", "🇦🇹"), result.map { it.emoji }.toSet())
    }

    @Test
    fun `crash details use the newest log format and replace ids in paths`() {
        val logs = listOf(
            Log(userId = me, logType = LogType.EXCEPTION_THROWN, timestamp = t0,
                message = "IllegalStateException: boom | path=/messages/5f1d7a9e8b3c2a1d4e6f7a8b/react | ip=1.2.3.4"),
            Log(userId = me, logType = LogType.EXCEPTION_THROWN, timestamp = t0 + 1.hours,
                message = "IllegalStateException: again | path=/messages/6a1d7a9e8b3c2a1d4e6f7a8c/react"),
            Log(userId = me, logType = LogType.EXCEPTION_THROWN, timestamp = t0 + 2.hours,
                message = "NullPointerException: no message | path=/users/sync"),
            Log(userId = me, logType = LogType.EXCEPTION_THROWN, timestamp = t0 - 100.hours, message = "old format | ip=1.2.3.4"),
            Log(userId = me, logType = LogType.EXCEPTION_THROWN, timestamp = t0 - 200.hours, message = null),
        )

        val details = crashDetails(logs)

        assertEquals(TextCount("IllegalStateException", 2L), details.topException)
        assertEquals(TextCount("/messages/{id}/react", 2L), details.topEndpoint)
        assertEquals((t0 - 200.hours).toEpochMilliseconds(), details.firstAt)
    }

    @Test
    fun `wakes sent from logs, received from system messages`() {
        fun wakeLog(target: ObjectId, isGroup: Boolean, reason: String, at: Instant) = Log(
            userId = me, logType = LogType.WAKE_SENT, timestamp = at,
            message = "Woke ${if (isGroup) "group" else "user"} ${target.toHexString()} on 2 device(s): $reason",
        )
        val logs = listOf(
            wakeLog(friend, false, "Wach uf!", t0),
            wakeLog(friend, false, "wach uf!", t0 + 1.hours),
            wakeLog(group, true, "Party", t0 + 2.hours),
            wakeLog(friend, false, "old", yearStartInstant - 1.hours),
        )
        val wakeEvent = { actor: ObjectId -> SystemEvent(eventType = SystemEventType.WAKE_SENT, actorId = actor) }
        val messages = listOf(
            message(friend, me, t0, type = MessageType.SYSTEM, systemEvent = wakeEvent(friend)),
            message(other, me, t0, type = MessageType.SYSTEM, systemEvent = wakeEvent(other)),
            message(friend, me, t0 + 1.hours, type = MessageType.SYSTEM, systemEvent = wakeEvent(friend)),
            message(group, group, t0, groupMessage = true, type = MessageType.SYSTEM, systemEvent = wakeEvent(other)),
            message(group, group, t0, groupMessage = true, type = MessageType.SYSTEM, systemEvent = wakeEvent(me)),
        )

        val recap = buildWakeRecap(logs, messages, me, yearStart, yearEnd) { id, isGroup ->
            if (isGroup) "Group" else names(id)
        }

        assertEquals(3L, recap.wakesSentThisYear)
        assertEquals(4L, recap.wakesSentAllTime)
        assertEquals(NamedCount(friend.toHexString(), "Friend", false, 2L), recap.mostWoken)
        assertEquals(TextCount("wach uf!", 2L), recap.topReason) // newest spelling wins
        assertEquals(3L, recap.wakesReceivedThisYear)
        assertEquals(NamedCount(friend.toHexString(), "Friend", false, 2L), recap.topWaker)
        assertEquals(1L, recap.groupWakesThisYear)
    }

    @Test
    fun `median of even and odd counts`() {
        assertNull(median(emptyList()))
        assertEquals(2L, median(listOf(3L, 1L, 2L)))
        assertEquals(15L, median(listOf(10L, 20L)))
    }
}
