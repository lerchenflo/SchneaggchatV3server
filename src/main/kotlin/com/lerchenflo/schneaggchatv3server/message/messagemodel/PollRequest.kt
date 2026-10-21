package com.lerchenflo.schneaggchatv3server.message.messagemodel

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import kotlin.time.Instant


/**
 * Request payload for creating a poll
 */
data class PollCreateRequest(
    @field:NotBlank(message = "Poll title must not be blank")
    @field:Size(max = 200, message = "Poll title too long")
    val title: String,
    @field:Size(max = 500, message = "Poll description too long")
    val description: String?,

    val maxAnswers: Int?, // null = unlimited
    val customAnswersEnabled: Boolean,
    val maxAllowedCustomAnswers: Int?, // null = unlimited

    val visibility: PollVisibility,

    val closeDate: Long?,

    val voteOptions: List<PollVoteOptionCreateRequest>,

    val allowDeleteOptions: Boolean = false,
    val showCheckboxes: Boolean = true,

    //Only read on sub polls, see PollMessage.visibleToAll
    val visibleToAll: Boolean = true,
)

/**
 * Data class needed for appending options when creating a poll
 */
data class PollVoteOptionCreateRequest(
    //Ids get assigned by the server
    @field:NotBlank(message = "Vote option text must not be blank")
    @field:Size(max = 250, message = "Vote option text too long")
    val text: String,
    val maxVoters: Int? = null, // null = unlimited
    val subPoll: PollCreateRequest? = null, //Follow-up poll for users who pick this option
)


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
 * Vote in a poll
 */
data class PollVoteRequest(
    @field:NotBlank(message = "Message ID must not be blank")
    @field:Size(max = 24, message = "Message ID too long")
    val messageId: String,
    @field:Size(max = 24, message = "Vote option ID too long")
    val id: String?, //Pass if available, else this is a new custom option
    @field:Size(max = 250, message = "Vote text too long")
    val text: String?, //Pass if the id is null (New custom option with this text)
    val maxAllowedAnswers: Int?, //Pass if the user creates a custom entry and the poll supports restricting the entries for votes
    val selected: Boolean, //Did the user select or unselect this item
    @field:Size(max = 24, message = "Parent option ID too long")
    val parentOptionId: String? = null, //Only for a new custom option: the option whose sub poll gets it (null = root poll)
)

/**
 * Delete a vote option from a poll
 */
data class PollOptionDeleteRequest(
    @field:NotBlank(message = "Message ID must not be blank")
    @field:Size(max = 24, message = "Message ID too long")
    val messageId: String,
    @field:NotBlank(message = "Option ID must not be blank")
    @field:Size(max = 24, message = "Option ID too long")
    val optionId: String,
)
