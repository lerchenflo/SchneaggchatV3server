package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollCreateRequest
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollMessage
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollVisibility
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollVoteOption
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollVoteOptionCreateRequest
import com.lerchenflo.schneaggchatv3server.message.messagemodel.PollVoter
import com.lerchenflo.schneaggchatv3server.message.messagemodel.toEditedPoll
import org.bson.types.ObjectId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PollEditMergeTest {

    private val creator = ObjectId()
    private val alice = ObjectId()
    private val bob = ObjectId()
    private val start = Instant.fromEpochMilliseconds(1_000_000)

    private fun vote(user: ObjectId, secondsLater: Int = 0) = PollVoter(user, start + secondsLater.seconds)

    private fun option(
        id: String,
        text: String,
        voters: List<PollVoter> = emptyList(),
        subPoll: PollMessage? = null,
        custom: Boolean = false,
        optionCreator: ObjectId = creator,
    ) = PollVoteOption(id = id, text = text, custom = custom, creatorId = optionCreator, voters = voters, subPoll = subPoll)

    private fun poll(
        vararg options: PollVoteOption,
        maxAnswers: Int? = null,
        visibility: PollVisibility = PollVisibility.PUBLIC,
        closeDate: Instant? = null,
    ) = PollMessage(
        creatorId = creator,
        title = "Title",
        description = null,
        maxAnswers = maxAnswers,
        customAnswersEnabled = false,
        maxAllowedCustomAnswers = null,
        visibility = visibility,
        closeDate = closeDate,
        voteOptions = options.toList(),
    )

    /** The request a client sends when the creator changes nothing. */
    private fun PollMessage.asRequest(): PollCreateRequest = PollCreateRequest(
        title = title,
        description = description,
        maxAnswers = maxAnswers,
        customAnswersEnabled = customAnswersEnabled,
        maxAllowedCustomAnswers = maxAllowedCustomAnswers,
        visibility = visibility,
        closeDate = closeDate?.toEpochMilliseconds(),
        voteOptions = voteOptions.map { it.asRequest() },
        allowDeleteOptions = allowDeleteOptions,
        showCheckboxes = showCheckboxes,
        visibleToAll = visibleToAll,
    )

    private fun PollVoteOption.asRequest() = PollVoteOptionCreateRequest(
        text = text,
        maxVoters = maxVoters,
        subPoll = subPoll?.asRequest(),
        id = id,
    )

    private fun PollCreateRequest.withOptions(vararg options: PollVoteOptionCreateRequest) =
        copy(voteOptions = options.toList())

    private fun PollMessage.option(id: String) = voteOptions.single { it.id == id }

    @Test
    fun `unchanged poll keeps every vote`() {
        val current = poll(option("a", "Yes", listOf(vote(alice))), option("b", "No", listOf(vote(bob))))

        assertEquals(current, current.asRequest().toEditedPoll(current, emptySet()))
    }

    @Test
    fun `changed text drops the voters of that option only`() {
        val current = poll(option("a", "Yes", listOf(vote(alice))), option("b", "No", listOf(vote(bob))))
        val request = current.asRequest().withOptions(
            current.option("a").asRequest().copy(text = "Maybe"),
            current.option("b").asRequest(),
        )

        val edited = request.toEditedPoll(current, emptySet())

        assertEquals("Maybe", edited.option("a").text)
        assertTrue(edited.option("a").voters.isEmpty())
        assertEquals(listOf(vote(bob)), edited.option("b").voters)
    }

    @Test
    fun `whitespace around the text is not a change`() {
        val current = poll(option("a", "Yes", listOf(vote(alice))))
        val request = current.asRequest().withOptions(current.option("a").asRequest().copy(text = "  Yes "))

        val edited = request.toEditedPoll(current, emptySet())

        assertEquals("Yes", edited.option("a").text)
        assertEquals(listOf(vote(alice)), edited.option("a").voters)
    }

    @Test
    fun `changed text drops the sub poll answers but keeps the sub poll`() {
        val subPoll = poll(option("s", "Sub", listOf(vote(alice)), custom = true, optionCreator = alice))
        val current = poll(option("a", "Yes", listOf(vote(alice)), subPoll = subPoll))
        val request = current.asRequest().withOptions(current.option("a").asRequest().copy(text = "Maybe"))

        val edited = request.toEditedPoll(current, emptySet()).option("a")

        val keptSubPoll = assertNotNull(edited.subPoll)
        assertEquals(listOf("s"), keptSubPoll.voteOptions.map { it.id })
        assertTrue(keptSubPoll.voteOptions.single().voters.isEmpty())
    }

    @Test
    fun `changed sub poll option drops only its own voters`() {
        val subPoll = poll(option("s1", "One", listOf(vote(alice))), option("s2", "Two", listOf(vote(bob))))
        val current = poll(option("a", "Yes", listOf(vote(alice), vote(bob)), subPoll = subPoll))
        val request = current.asRequest().withOptions(
            current.option("a").asRequest().copy(
                subPoll = subPoll.asRequest().withOptions(
                    subPoll.option("s1").asRequest().copy(text = "Changed"),
                    subPoll.option("s2").asRequest(),
                )
            )
        )

        val edited = request.toEditedPoll(current, emptySet()).option("a")

        assertEquals(2, edited.voters.size)
        assertTrue(edited.subPoll!!.option("s1").voters.isEmpty())
        assertEquals(listOf(vote(bob)), edited.subPoll.option("s2").voters)
    }

    @Test
    fun `deleted option is removed with its sub poll`() {
        val current = poll(option("a", "Yes", subPoll = poll(option("s", "Sub"))), option("b", "No"))
        val request = current.asRequest().withOptions(current.option("b").asRequest())

        val edited = request.toEditedPoll(current, setOf("a"))

        assertEquals(listOf("b"), edited.voteOptions.map { it.id })
    }

    @Test
    fun `sub poll option can be deleted through the shared id list`() {
        val subPoll = poll(option("s1", "One"), option("s2", "Two"))
        val current = poll(option("a", "Yes", subPoll = subPoll))
        val request = current.asRequest().withOptions(
            current.option("a").asRequest().copy(subPoll = subPoll.asRequest().withOptions(subPoll.option("s2").asRequest()))
        )

        val edited = request.toEditedPoll(current, setOf("s1"))

        assertEquals(listOf("s2"), edited.option("a").subPoll!!.voteOptions.map { it.id })
    }

    @Test
    fun `option added while the editor was open stays`() {
        val seen = option("a", "Yes", listOf(vote(alice)))
        val unseen = option("b", "New", listOf(vote(bob)), custom = true, optionCreator = bob)
        val current = poll(seen, unseen)
        val request = current.asRequest().withOptions(seen.asRequest())

        val edited = request.toEditedPoll(current, emptySet())

        assertEquals(listOf("a", "b"), edited.voteOptions.map { it.id })
        assertEquals(unseen, edited.option("b"))
    }

    @Test
    fun `new option gets a server id and no votes`() {
        val current = poll(option("a", "Yes"))
        val request = current.asRequest().withOptions(
            current.option("a").asRequest(),
            PollVoteOptionCreateRequest(text = "No"),
        )

        val added = request.toEditedPoll(current, emptySet()).voteOptions.last()

        assertTrue(ObjectId.isValid(added.id))
        assertEquals("No", added.text)
        assertEquals(creator, added.creatorId)
        assertFalse(added.custom)
        assertTrue(added.voters.isEmpty())
    }

    @Test
    fun `options follow the order of the request`() {
        val current = poll(option("a", "Yes"), option("b", "No"))
        val request = current.asRequest().withOptions(current.option("b").asRequest(), current.option("a").asRequest())

        assertEquals(listOf("b", "a"), request.toEditedPoll(current, emptySet()).voteOptions.map { it.id })
    }

    @Test
    fun `custom option keeps its owner when the creator changes the text`() {
        val current = poll(option("a", "Mine", custom = true, optionCreator = alice))
        val request = current.asRequest().withOptions(current.option("a").asRequest().copy(text = "Changed"))

        val edited = request.toEditedPoll(current, emptySet()).option("a")

        assertTrue(edited.custom)
        assertEquals(alice, edited.creatorId)
    }

    @Test
    fun `sub poll added to a custom option belongs to the poll creator`() {
        val current = poll(option("a", "Mine", listOf(vote(alice)), custom = true, optionCreator = alice))
        val newSubPoll = PollCreateRequest(
            title = "Sub",
            description = null,
            maxAnswers = null,
            customAnswersEnabled = false,
            maxAllowedCustomAnswers = null,
            visibility = PollVisibility.PUBLIC,
            closeDate = null,
            voteOptions = listOf(PollVoteOptionCreateRequest(text = "One")),
            visibleToAll = false,
        )
        val request = current.asRequest().withOptions(current.option("a").asRequest().copy(subPoll = newSubPoll))

        val edited = request.toEditedPoll(current, emptySet()).option("a")

        assertEquals(creator, edited.subPoll!!.creatorId)
        assertFalse(edited.subPoll.visibleToAll)
        assertEquals(listOf(vote(alice)), edited.voters)
    }

    @Test
    fun `missing sub poll in the request removes it`() {
        val current = poll(option("a", "Yes", listOf(vote(alice)), subPoll = poll(option("s", "Sub"))))
        val request = current.asRequest().withOptions(current.option("a").asRequest().copy(subPoll = null))

        val edited = request.toEditedPoll(current, emptySet()).option("a")

        assertNull(edited.subPoll)
        assertEquals(listOf(vote(alice)), edited.voters)
    }

    @Test
    fun `unknown, duplicate and contradicting ids are rejected`() {
        val current = poll(option("a", "Yes"), option("b", "No"))
        val a = current.option("a").asRequest()

        assertFailsWith<IllegalArgumentException> {
            current.asRequest().withOptions(a.copy(id = "gone")).toEditedPoll(current, emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            current.asRequest().withOptions(a, a).toEditedPoll(current, emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            current.asRequest().withOptions(a).toEditedPoll(current, setOf("a"))
        }
    }

    @Test
    fun `an option can not move to another poll level`() {
        val subPoll = poll(option("s", "Sub", listOf(vote(alice))))
        val current = poll(option("a", "Yes", subPoll = subPoll))
        val request = current.asRequest().withOptions(current.option("a").asRequest(), subPoll.option("s").asRequest())

        assertFailsWith<IllegalArgumentException> { request.toEditedPoll(current, emptySet()) }
    }

    @Test
    fun `visibility can not get looser`() {
        val anonymous = poll(option("a", "Yes"), visibility = PollVisibility.ANONYMOUS)
        val private = poll(option("a", "Yes"), visibility = PollVisibility.PRIVATE)

        assertFailsWith<IllegalArgumentException> {
            anonymous.asRequest().copy(visibility = PollVisibility.PUBLIC).toEditedPoll(anonymous, emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            anonymous.asRequest().copy(visibility = PollVisibility.PRIVATE).toEditedPoll(anonymous, emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            private.asRequest().copy(visibility = PollVisibility.PUBLIC).toEditedPoll(private, emptySet())
        }
    }

    @Test
    fun `stricter visibility keeps the votes`() {
        val current = poll(option("a", "Yes", listOf(vote(alice))))

        val edited = current.asRequest().copy(visibility = PollVisibility.PRIVATE).toEditedPoll(current, emptySet())

        assertEquals(PollVisibility.PRIVATE, edited.visibility)
        assertEquals(listOf(vote(alice)), edited.option("a").voters)
    }

    @Test
    fun `stricter visibility reaches every sub poll, also below unseen options`() {
        val seen = option("a", "Yes", listOf(vote(alice)), subPoll = poll(option("s", "Sub", listOf(vote(alice)))))
        val unseen = option("b", "New", subPoll = poll(option("t", "Sub")))
        val current = poll(seen, unseen)
        val request = current.asRequest().withOptions(seen.asRequest()).copy(visibility = PollVisibility.ANONYMOUS)

        val edited = request.toEditedPoll(current, emptySet())

        assertEquals(PollVisibility.ANONYMOUS, edited.option("a").subPoll!!.visibility)
        assertEquals(PollVisibility.ANONYMOUS, edited.option("b").subPoll!!.visibility)
        assertTrue(edited.option("a").subPoll!!.isAnonymous(creator))
    }

    @Test
    fun `checkboxes are locked`() {
        val current = poll(option("a", "Yes"))

        assertFailsWith<IllegalArgumentException> {
            current.asRequest().copy(showCheckboxes = false).toEditedPoll(current, emptySet())
        }
    }

    @Test
    fun `lower maxAnswers drops the oldest votes and their sub poll answers`() {
        val subPoll = poll(option("s", "Sub", listOf(vote(alice))))
        val current = poll(
            option("a", "Old", listOf(vote(alice, 1), vote(bob, 1)), subPoll = subPoll),
            option("b", "New", listOf(vote(alice, 2))),
        )

        val edited = current.asRequest().copy(maxAnswers = 1).toEditedPoll(current, emptySet())

        assertEquals(listOf(vote(bob, 1)), edited.option("a").voters)
        assertTrue(edited.option("a").subPoll!!.voteOptions.single().voters.isEmpty())
        assertEquals(listOf(vote(alice, 2)), edited.option("b").voters)
    }

    @Test
    fun `new close date reaches every sub poll, also below unseen options`() {
        val closeDate = Instant.fromEpochMilliseconds(5_000_000)
        val seen = option("a", "Yes", subPoll = poll(option("s", "Sub")))
        val unseen = option("b", "New", subPoll = poll(option("t", "Sub")))
        val current = poll(seen, unseen)
        val request = current.asRequest().withOptions(seen.asRequest()).copy(closeDate = closeDate.toEpochMilliseconds())

        val edited = request.toEditedPoll(current, emptySet())

        assertEquals(closeDate, edited.closeDate)
        assertEquals(closeDate, edited.option("a").subPoll!!.closeDate)
        assertEquals(closeDate, edited.option("b").subPoll!!.closeDate)
    }
}
