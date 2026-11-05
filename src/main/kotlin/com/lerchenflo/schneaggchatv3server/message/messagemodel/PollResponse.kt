package com.lerchenflo.schneaggchatv3server.message.messagemodel

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

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
    val visibleToAll: Boolean



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
        override val visibleToAll: Boolean,

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
        override val visibleToAll: Boolean,

        val voteOptions: List<AnonymousPollVoteOptionResponse>,

        ) : PollResponse

}


data class AnonymousPollVoteOptionResponse(
    val id: String,
    val text: String,
    val custom: Boolean,
    val createdByMe: Boolean,
    val maxVoters: Int? = null, // null = unlimited
    val voters : List<AnonymousPollVoterResponse>,
    val subPoll: PollResponse? = null,
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
    val voters : List<PublicPollVoterResponse>,
    val subPoll: PollResponse? = null,
)

data class PublicPollVoterResponse(
    val userId: String,
    val votedAt: Long,
)
