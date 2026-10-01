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

    //If true, the creator may delete any option, and users may delete options they created
    val allowDeleteOptions: Boolean = false,

    //If false, the poll renders as a plain read-only list - no voting, no checkboxes
    val showCheckboxes: Boolean = true,

    //Only meaningful on a sub poll: true = users who did not pick the parent option may still see it
    //(read-only), false = it is only sent to the poll creator and to users who picked the parent option
    val visibleToAll: Boolean = true,
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

    fun canUserDeleteOption(userId: ObjectId, option: PollVoteOption): Boolean =
        allowDeleteOptions && (creatorId == userId || option.creatorId == userId)

    /**
     * Options from this poll down to (and including) the option with [optionId], walking into sub polls.
     * Null if no option in this poll tree has that id.
     */
    fun optionPath(optionId: String): List<PollVoteOption>? {
        voteOptions.forEach { option ->
            if (option.id == optionId) return listOf(option)
            option.subPoll?.optionPath(optionId)?.let { return listOf(option) + it }
        }
        return null
    }

    /**
     * Returns this poll tree with [transform] applied to the sub poll of [parentOptionId],
     * or to this poll itself if [parentOptionId] is null.
     */
    fun updatePoll(parentOptionId: String?, transform: (PollMessage) -> PollMessage): PollMessage {
        if (parentOptionId == null) return transform(this)
        return copy(
            voteOptions = voteOptions.map { option ->
                if (option.id == parentOptionId) {
                    option.copy(subPoll = option.subPoll?.let(transform))
                } else {
                    option.copy(subPoll = option.subPoll?.updatePoll(parentOptionId, transform))
                }
            }
        )
    }

    /** Removes every vote of [userId] in this poll and all of its sub polls. */
    fun clearVotesOf(userId: ObjectId): PollMessage = copy(
        voteOptions = voteOptions.map { it.clearVotesOf(userId) }
    )

    /** Number of options in this poll and all of its sub polls. */
    fun totalOptionCount(): Int = voteOptions.sumOf { 1 + (it.subPoll?.totalOptionCount() ?: 0) }
}

data class PollVoteOption(
    val id: String,
    val text: String,
    val custom: Boolean,
    val creatorId: ObjectId,
    val voters : List<PollVoter>,
    val maxVoters: Int? = null, // null = unlimited

    //Follow-up poll that only matters to users who picked this option
    val subPoll: PollMessage? = null,
) {
    fun hasVoter(userId: ObjectId): Boolean = voters.any { it.userId == userId }

    /** Removes [userId]'s vote on this option and every vote they cast in its sub poll tree. */
    fun clearVotesOf(userId: ObjectId): PollVoteOption = copy(
        voters = voters.filter { it.userId != userId },
        subPoll = subPoll?.clearVotesOf(userId)
    )
}

data class PollVoter(
    val userId: ObjectId,
    val votedAt: Instant,
)

enum class PollVisibility{
    PUBLIC,
    PRIVATE,
    ANONYMOUS
}