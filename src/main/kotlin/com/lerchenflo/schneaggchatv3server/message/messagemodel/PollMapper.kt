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
        voteOptions = this.voteOptions.map {
            PollVoteOption(
                id = ObjectId.get().toHexString(),
                text = it.text,
                custom = false,
                creatorId = creatorId,
                voters = emptyList(),
                maxVoters = it.maxVoters,
                subPoll = it.subPoll?.toPoll(creatorId = creatorId, root = root),
            )
        },
        allowDeleteOptions = this.allowDeleteOptions,
        showCheckboxes = this.showCheckboxes,
        visibleToAll = if (this === root) true else this.visibleToAll,
    )
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
