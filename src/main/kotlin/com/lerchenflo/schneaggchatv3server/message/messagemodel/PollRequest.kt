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
)


fun PollCreateRequest.toPoll(creatorId: ObjectId) : PollMessage {
    return PollMessage(
        creatorId = creatorId,
        title = this.title.trim(),
        description = this.description?.trim(),
        maxAnswers = this.maxAnswers,
        customAnswersEnabled = this.customAnswersEnabled,
        maxAllowedCustomAnswers = this.maxAllowedCustomAnswers,
        visibility = this.visibility,
        closeDate = if (this.closeDate != null) Instant.fromEpochMilliseconds(this.closeDate) else null,
        voteOptions = this.voteOptions.map {
            PollVoteOption(
                id = ObjectId.get().toHexString(),
                text = it.text,
                custom = false,
                creatorId = creatorId,
                voters = emptyList(),
                maxVoters = it.maxVoters,
            )
        }
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
    val selected: Boolean, //Did the user select or unselect this item
)
