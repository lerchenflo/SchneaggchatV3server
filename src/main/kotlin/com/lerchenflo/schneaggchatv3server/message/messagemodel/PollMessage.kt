package com.lerchenflo.schneaggchatv3server.message.messagemodel

import com.lerchenflo.schneaggchatv3server.util.Json
import org.bson.types.ObjectId
import kotlin.time.Instant

data class PollMessage(
    val creatorId: ObjectId,
    val title: String,
    val description: String?,


    //Max answers per user
    val maxAnswers: Int?, // null = unlimited

    //Custom answers enabled
    val customAnswersEnabled: Boolean,

    //Max allowed custom answers per user
    val maxAllowedCustomAnswers: Int?, // null = unlimited

    val visibility: PollVisibility,


    val closeDate: Instant?,

    val voteOptions: List<PollVoteOption> = emptyList(),
) {

    fun toJson(): String = Json.mapper.writeValueAsString(this)

    fun isAnonymous(requestingUserId: ObjectId): Boolean {

        //Return true if poll is anonymous
        if (visibility == PollVisibility.ANONYMOUS) return true

        //Return false for the creator on a private poll
        if (visibility == PollVisibility.PRIVATE && requestingUserId == creatorId) return false

        if (visibility == PollVisibility.PUBLIC) return false

        return true
    }


    fun getVoteCountForUser(userId: ObjectId): Int {
        return voteOptions.sumOf { option ->
            option.voters.count { it.userId == userId }
        }
    }

    fun getCustomVoteCountForUser(userId: ObjectId): Int {
        return voteOptions.count { it.custom && it.creatorId == userId }
    }
}

data class PollVoteOption(
    val id: String,
    val text: String,
    val custom: Boolean,
    val creatorId: ObjectId,
    val voters : List<PollVoter>,
    val maxVoters: Int? = null, // null = unlimited
)

data class PollVoter(
    val userId: ObjectId,
    val votedAt: Instant,
)

enum class PollVisibility{
    PUBLIC,
    PRIVATE,
    ANONYMOUS
}