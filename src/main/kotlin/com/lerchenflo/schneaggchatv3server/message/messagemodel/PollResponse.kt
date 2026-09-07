package com.lerchenflo.schneaggchatv3server.message.messagemodel

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.bson.types.ObjectId


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
                voteOptions = poll.voteOptions.map { option ->
                    AnonymousPollVoteOptionResponse(
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
                voteOptions = poll.voteOptions.map { option ->
                    PublicPollVoteOptionResponse(
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


@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "_class"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = PollResponse.PublicPollResponse::class, name = "public"),
    JsonSubTypes.Type(value = PollResponse.AnonymousPollResponse::class, name = "anonymous"),
)

interface PollResponse {

    val creatorId: String
    val title: String
    val description: String?


    val maxAnswers: Int? // null = unlimited
    val customAnswersEnabled: Boolean
    val maxAllowedCustomAnswers: Int? // null = unlimited

    val visibility: PollVisibility


    val closeDate: Long?

    val allowDeleteOptions: Boolean
    val showCheckboxes: Boolean



    data class PublicPollResponse (
        override val creatorId: String,
        override val title: String,
        override val description: String?,
        override val maxAnswers: Int?,
        override val customAnswersEnabled: Boolean,
        override val maxAllowedCustomAnswers: Int?,
        override val visibility: PollVisibility,
        override val closeDate: Long?,
        override val allowDeleteOptions: Boolean,
        override val showCheckboxes: Boolean,

        val voteOptions: List<PublicPollVoteOptionResponse>,

        ) : PollResponse

    data class AnonymousPollResponse (
        override val creatorId: String,
        override val title: String,
        override val description: String?,
        override val maxAnswers: Int?,
        override val customAnswersEnabled: Boolean,
        override val maxAllowedCustomAnswers: Int?,
        override val visibility: PollVisibility,
        override val closeDate: Long?,
        override val allowDeleteOptions: Boolean,
        override val showCheckboxes: Boolean,

        val voteOptions: List<AnonymousPollVoteOptionResponse>,

        ) : PollResponse

}


data class AnonymousPollVoteOptionResponse(
    val id: String,
    val text: String,
    val custom: Boolean,
    val createdByMe: Boolean,
    val maxVoters: Int? = null, // null = unlimited
    val voters : List<AnonymousPollVoterResponse>
)

data class AnonymousPollVoterResponse(
    val myAnswer: Boolean,
    val votedAt: Long,
)




data class PublicPollVoteOptionResponse(
    val id: String,
    val text: String,
    val custom: Boolean,
    val creatorId: String,
    val maxVoters: Int? = null, // null = unlimited
    val voters : List<PublicPollVoterResponse>
)

data class PublicPollVoterResponse(
    val userId: String,
    val votedAt: Long,
)