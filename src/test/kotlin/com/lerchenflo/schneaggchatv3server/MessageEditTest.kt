package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.message.MessageLookupService
import com.lerchenflo.schneaggchatv3server.message.MessageService
import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollCreateRequest
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollEditRequest
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollMessage
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollVisibility
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollVoteOption
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollVoteOptionCreateRequest
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollVoter
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.util.VersionCounterService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.UpdateDefinition
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class MessageEditTest {

    private val mongoTemplate = mockk<MongoTemplate>(relaxed = true)
    private val lookup = mockk<MessageLookupService>(relaxed = true)
    private val notifications = mockk<NotificationService>(relaxed = true)
    private val versions = mockk<VersionCounterService>()

    private val service = MessageService(
        mongoTemplate = mongoTemplate,
        messageLookupService = lookup,
        friendsService = mockk(relaxed = true),
        friendsLookupService = mockk(relaxed = true),
        groupLookupService = mockk(relaxed = true),
        userLookupService = mockk(relaxed = true),
        imageManager = mockk(relaxed = true),
        audioManager = mockk(relaxed = true),
        notificationService = notifications,
        loggingService = mockk(relaxed = true),
        versionCounterService = versions,
    )

    private val sender = ObjectId()
    private val other = ObjectId()
    private val writes = slot<UpdateDefinition>()

    init {
        every { versions.withVersion<Message?>(any(), any()) } answers { secondArg<(Long) -> Message?>().invoke(1L) }
    }

    private fun message(
        type: MessageType = MessageType.TEXT,
        poll: PollMessage? = null,
        deleted: Boolean = false,
    ) = Message(
        senderId = sender,
        receiverId = other,
        groupMessage = false,
        msgType = type,
        answerId = null,
        content = "text",
        poll = poll,
        sendDate = Instant.fromEpochMilliseconds(1_000),
        lastChanged = Instant.fromEpochMilliseconds(1_000),
        deleted = deleted,
        readers = emptyList(),
    )

    private fun option(id: String, vararg voters: ObjectId) = PollVoteOption(
        id = id,
        text = "Option $id",
        custom = false,
        creatorId = sender,
        voters = voters.map { PollVoter(it, Instant.fromEpochMilliseconds(2_000)) },
    )

    private fun poll(vararg options: PollVoteOption, closeDate: Instant? = null) = PollMessage(
        creatorId = sender,
        title = "Title",
        description = null,
        maxAnswers = null,
        customAnswersEnabled = false,
        maxAllowedCustomAnswers = null,
        visibility = PollVisibility.PUBLIC,
        closeDate = closeDate,
        voteOptions = options.toList(),
    )

    private fun editOf(message: Message, closeDate: Instant? = message.poll?.closeDate) = PollEditRequest(
        messageId = message.id.toHexString(),
        poll = PollCreateRequest(
            title = "New title",
            description = null,
            maxAnswers = null,
            customAnswersEnabled = false,
            maxAllowedCustomAnswers = null,
            visibility = PollVisibility.PUBLIC,
            closeDate = closeDate?.toEpochMilliseconds(),
            voteOptions = message.poll!!.voteOptions.map { PollVoteOptionCreateRequest(text = it.text, id = it.id) },
        ),
    )

    private fun writeReturns(vararg results: Message?) {
        every {
            mongoTemplate.findAndModify(any<Query>(), capture(writes), any<FindAndModifyOptions>(), Message::class.java)
        } returnsMany results.toList()
    }

    private fun writtenFields() = (writes.captured as Update).updateObject["\$set"] as Document

    @Test
    fun `sender can edit a text message`() {
        val message = message()
        every { lookup.findById(message.id) } returns message
        writeReturns(message)

        service.editMessage(message.id, sender, "new text")

        assertEquals("new text", writtenFields()["content"])
        assertEquals(true, writtenFields()["edited"])
        verify(exactly = 1) { notifications.notifyMessageUpdate(message, false, false, sender) }
    }

    @Test
    fun `chat partner can not edit a text message`() {
        val message = message()
        every { lookup.findById(message.id) } returns message

        assertFailsWith<IllegalArgumentException> { service.editMessage(message.id, other, "changed by someone else") }

        verify(exactly = 0) { mongoTemplate.findAndModify(any<Query>(), any<UpdateDefinition>(), any<FindAndModifyOptions>(), Message::class.java) }
    }

    @Test
    fun `deleted message can not be edited`() {
        val message = message(deleted = true)
        every { lookup.findById(message.id) } returns message

        assertFailsWith<IllegalArgumentException> { service.editMessage(message.id, sender, "new text") }
    }

    @Test
    fun `text edit retries when another request wrote first`() {
        val message = message()
        every { lookup.findById(message.id) } returns message
        writeReturns(null, message)

        service.editMessage(message.id, sender, "new text")

        verify(exactly = 2) { lookup.findById(message.id) }
        verify(exactly = 1) { notifications.notifyMessageUpdate(any(), any(), any(), any()) }
    }

    @Test
    fun `chat partner can not edit a poll`() {
        val message = message(MessageType.POLL, poll(option("a"), option("b")))
        every { lookup.findById(message.id) } returns message

        assertFailsWith<IllegalArgumentException> { service.editPoll(other, editOf(message)) }
    }

    @Test
    fun `poll edit on a text message is rejected`() {
        val message = message(MessageType.POLL, poll(option("a"))).copy(msgType = MessageType.TEXT)
        every { lookup.findById(message.id) } returns message

        assertFailsWith<IllegalArgumentException> { service.editPoll(sender, editOf(message)) }
    }

    @Test
    fun `vote cast during an edit survives the retry`() {
        val before = message(MessageType.POLL, poll(option("a"), option("b")))
        val afterVote = before.copy(poll = poll(option("a", other), option("b")))
        every { lookup.findById(before.id) } returnsMany listOf(before, afterVote)
        writeReturns(null, afterVote)

        service.editPoll(sender, editOf(before))

        val written = writtenFields()["poll"] as PollMessage
        assertEquals("New title", written.title)
        assertEquals(listOf(other), written.voteOptions.first { it.id == "a" }.voters.map { it.userId })
        assertEquals(true, writtenFields()["edited"])
    }

    @Test
    fun `closed poll can be edited without reopening it`() {
        val closed = Clock.System.now() - 1.hours
        val message = message(MessageType.POLL, poll(option("a"), closeDate = Instant.fromEpochMilliseconds(closed.toEpochMilliseconds())))
        every { lookup.findById(message.id) } returns message
        writeReturns(message)

        service.editPoll(sender, editOf(message))

        assertEquals(message.poll!!.closeDate, (writtenFields()["poll"] as PollMessage).closeDate)
    }

    @Test
    fun `new close date must be in the future`() {
        val message = message(MessageType.POLL, poll(option("a"), closeDate = Clock.System.now() - 2.hours))
        every { lookup.findById(message.id) } returns message

        assertFailsWith<IllegalArgumentException> {
            service.editPoll(sender, editOf(message, closeDate = Clock.System.now() - 1.hours))
        }
    }

    @Test
    fun `closed poll can be reopened`() {
        val message = message(MessageType.POLL, poll(option("a"), closeDate = Clock.System.now() - 1.hours))
        val reopened = Clock.System.now() + 1.hours
        every { lookup.findById(message.id) } returns message
        writeReturns(message)

        service.editPoll(sender, editOf(message, closeDate = reopened))

        assertEquals(reopened.toEpochMilliseconds(), (writtenFields()["poll"] as PollMessage).closeDate?.toEpochMilliseconds())
    }

    @Test
    fun `poll grown past the creation limit stays editable`() {
        val message = message(MessageType.POLL, poll(*Array(30) { option("o$it") }))
        every { lookup.findById(message.id) } returns message
        writeReturns(message)

        service.editPoll(sender, editOf(message))

        assertEquals(30, (writtenFields()["poll"] as PollMessage).voteOptions.size)
    }
}
