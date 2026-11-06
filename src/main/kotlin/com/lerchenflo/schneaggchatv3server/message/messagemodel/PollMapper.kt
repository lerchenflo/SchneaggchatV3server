package com.lerchenflo.schneaggchatv3server.message.messagemodel

import org.bson.types.ObjectId
import kotlin.time.Instant

/**
 * Builds the poll tree. Sub polls always inherit visibility and close date from [root] - whatever
 * a sub poll request carries for those is ignored.
 */
fun PollCreateRequest.toPoll(creatorId: ObjectId, root: PollCreateRequest = this) : PollMessage {
    return PollMessage(
        creatorId = creatorId,
        title = this.title.trim(),
        description = this.description?.trim(),
        maxAnswers = this.maxAnswers,
        customAnswersEnabled = this.customAnswersEnabled,
        maxAllowedCustomAnswers = this.maxAllowedCustomAnswers,
        visibility = root.visibility,
        closeDate = if (root.closeDate != null) Instant.fromEpochMilliseconds(root.closeDate) else null,
        voteOptions = this.voteOptions.map { it.toVoteOption(creatorId, root) },
        allowDeleteOptions = this.allowDeleteOptions,
        showCheckboxes = this.showCheckboxes,
        visibleToAll = if (this === root) true else this.visibleToAll,
    )
}

private fun PollVoteOptionCreateRequest.toVoteOption(creatorId: ObjectId, root: PollCreateRequest) = PollVoteOption(
    id = ObjectId.get().toHexString(),
    text = this.text,
    custom = false,
    creatorId = creatorId,
    voters = emptyList(),
    maxVoters = this.maxVoters,
    subPoll = this.subPoll?.toPoll(creatorId = creatorId, root = root),
)

/**
 * Applies a creator's edit to [current], the poll as stored right now. Votes always come from
 * [current], never from the request, so call this again on a retry to keep votes cast meanwhile.
 *
 * Per poll level: an option with an id keeps its votes unless its text changed, an option without
 * an id is new, and only options in [deletedOptionIds] are removed. An option that is missing from
 * the request without being deleted was added after the creator opened the editor, so it stays.
 *
 * Visibility may only get stricter, because votes were cast under the current one and a looser
 * value would show more of them. showCheckboxes is locked: a list poll can't hold votes or sub polls.
 */
fun PollCreateRequest.toEditedPoll(current: PollMessage, deletedOptionIds: Set<String>): PollMessage {
    require(this.visibility >= current.visibility) { "Poll visibility can only get stricter" }

    return editLevel(current, deletedOptionIds, root = this)
        .withRootSettings(this.visibility, this.closeDate?.let { Instant.fromEpochMilliseconds(it) })
}

private fun PollCreateRequest.editLevel(current: PollMessage, deletedOptionIds: Set<String>, root: PollCreateRequest): PollMessage {
    require(this.showCheckboxes == current.showCheckboxes) { "Poll checkboxes can not be changed" }

    val currentOptions = current.voteOptions.associateBy { it.id }
    val requestedIds = this.voteOptions.mapNotNull { it.id }
    require(requestedIds.distinct().size == requestedIds.size) { "Duplicate vote option id" }
    require(currentOptions.keys.containsAll(requestedIds)) { "Unknown vote option id" }
    require(requestedIds.none { it in deletedOptionIds }) { "Vote option is both kept and deleted" }

    val editedOptions = this.voteOptions.map { option ->
        option.id?.let { currentOptions.getValue(it).applyEdit(option, current.creatorId, deletedOptionIds, root) }
            ?: option.toVoteOption(current.creatorId, root)
    }
    val unseenOptions = current.voteOptions.filter { it.id !in requestedIds && it.id !in deletedOptionIds }

    return current.copy(
        title = this.title.trim(),
        description = this.description?.trim(),
        maxAnswers = this.maxAnswers,
        customAnswersEnabled = this.customAnswersEnabled,
        maxAllowedCustomAnswers = this.maxAllowedCustomAnswers,
        voteOptions = editedOptions + unseenOptions,
        allowDeleteOptions = this.allowDeleteOptions,
        visibleToAll = if (this === root) true else this.visibleToAll,
    ).dropVotesOverMaxAnswers()
}

private fun PollVoteOption.applyEdit(
    edit: PollVoteOptionCreateRequest,
    pollCreatorId: ObjectId,
    deletedOptionIds: Set<String>,
    root: PollCreateRequest,
): PollVoteOption {
    val currentSubPoll = this.subPoll
    val editedSubPoll = when {
        edit.subPoll == null -> null
        currentSubPoll == null -> edit.subPoll.toPoll(creatorId = pollCreatorId, root = root)
        else -> edit.subPoll.editLevel(currentSubPoll, deletedOptionIds, root)
    }
    val edited = copy(maxVoters = edit.maxVoters, subPoll = editedSubPoll)

    //A vote was cast for the old text. Sub poll answers need that parent vote, so they go with it.
    val newText = edit.text.trim()
    return if (newText == this.text.trim()) edited else edited.copy(text = newText).clearAllVotes()
}

/**
 * A sub poll is sent along if it is visible to everyone, or the requester created the poll, or
 * picked the option it belongs to - otherwise it is left out of this user's response entirely.
 */
private fun PollVoteOption.subPollResponseFor(requestingUserId: ObjectId, pollCreatorId: ObjectId): PollResponse? {
    val subPoll = subPoll ?: return null
    val visible = subPoll.visibleToAll || requestingUserId == pollCreatorId || hasVoter(requestingUserId)
    return if (visible) subPoll.toPollMessageResponse(requestingUserId) else null
}

fun PollMessage.toPollMessageResponse(requestingUserId: ObjectId): PollResponse {
    val poll = this

    return when (poll.isAnonymous(requestingUserId)) {
        true -> {
            PollResponse.AnonymousPollResponse(
                creatorId = poll.creatorId.toHexString(),
                title = poll.title,
                description = poll.description,
                maxAnswers = poll.maxAnswers,
                customAnswersEnabled = poll.customAnswersEnabled,
                maxAllowedCustomAnswers = poll.maxAllowedCustomAnswers,
                visibility = poll.visibility,
                closeDate = poll.closeDate?.toEpochMilliseconds(),
                allowDeleteOptions = poll.allowDeleteOptions,
                showCheckboxes = poll.showCheckboxes,
                visibleToAll = poll.visibleToAll,
                voteOptions = poll.voteOptions.map { option ->
                    AnonymousPollVoteOptionResponse(
                        subPoll = option.subPollResponseFor(requestingUserId, poll.creatorId),
                        id = option.id,
                        text = option.text,
                        custom = option.custom,
                        createdByMe = option.creatorId == requestingUserId,
                        maxVoters = option.maxVoters,
                        voters = option.voters.map { voter ->
                            AnonymousPollVoterResponse(
                                votedAt = voter.votedAt.toEpochMilliseconds(),
                                myAnswer = voter.userId == requestingUserId
                            )
                        }
                    )
                }
            )
        }
        false -> {
            PollResponse.PublicPollResponse(
                creatorId = poll.creatorId.toHexString(),
                title = poll.title,
                description = poll.description,
                maxAnswers = poll.maxAnswers,
                customAnswersEnabled = poll.customAnswersEnabled,
                maxAllowedCustomAnswers = poll.maxAllowedCustomAnswers,
                visibility = poll.visibility,
                closeDate = poll.closeDate?.toEpochMilliseconds(),
                allowDeleteOptions = poll.allowDeleteOptions,
                showCheckboxes = poll.showCheckboxes,
                visibleToAll = poll.visibleToAll,
                voteOptions = poll.voteOptions.map { option ->
                    PublicPollVoteOptionResponse(
                        subPoll = option.subPollResponseFor(requestingUserId, poll.creatorId),
                        id = option.id,
                        text = option.text,
                        custom = option.custom,
                        creatorId = option.creatorId.toHexString(),
                        maxVoters = option.maxVoters,
                        voters = option.voters.map { voter ->
                            PublicPollVoterResponse(
                                userId = voter.userId.toHexString(),
                                votedAt = voter.votedAt.toEpochMilliseconds()
                            )
                        }
                    )
                }
            )
        }
    }
}
