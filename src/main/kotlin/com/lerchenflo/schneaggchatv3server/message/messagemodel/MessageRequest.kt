package com.lerchenflo.schneaggchatv3server.message.messagemodel

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class MessageRequest(
    @field:Size(max = 24, message = "Message ID too long")
    val messageId: String?, //Objectid

    @field:NotBlank(message = "Receiver ID must not be blank")
    @field:Size(max = 24, message = "Receiver ID too long")
    val receiverId: String,
    val groupMessage: Boolean,
    val msgType: MessageType,
    @field:NotBlank(message = "Content must not be blank")
    @field:Size(max = 10000, message = "Content too long (max 10000)")
    val content: String,
    @field:Size(max = 24, message = "Answer ID too long")
    val answerId: String?,
    @field:NotBlank(message = "Client message ID must not be blank")
    @field:Size(max = 36, message = "Client message ID too long")
    val clientMessageId: String,
)

data class PollMessageRequest(
    @field:NotBlank(message = "Receiver ID must not be blank")
    @field:Size(max = 24, message = "Receiver ID too long")
    val receiverId: String,
    val groupMessage: Boolean,
    val msgType: MessageType,
    @field:Size(max = 24, message = "Answer ID too long")
    val answerId: String?,
    val poll: PollCreateRequest,
    @field:NotBlank(message = "Client message ID must not be blank")
    @field:Size(max = 36, message = "Client message ID too long")
    val clientMessageId: String,
)

data class ImageMessageRequest(
    @field:Size(max = 24, message = "Message ID too long")
    val messageId: String?, //Objectid

    @field:NotBlank(message = "Receiver ID must not be blank")
    @field:Size(max = 24, message = "Receiver ID too long")
    val receiverId: String,
    val groupMessage: Boolean,
    val msgType: MessageType,
    @field:Size(max = 10000, message = "Content too long (max 10000)")
    val content: String,
    @field:Size(max = 24, message = "Answer ID too long")
    val answerId: String?,
    @field:NotBlank(message = "Client message ID must not be blank")
    @field:Size(max = 36, message = "Client message ID too long")
    val clientMessageId: String,
)

data class ReactionRequest(
    @field:NotBlank(message = "Message ID must not be blank")
    @field:Size(max = 24, message = "Message ID too long")
    val messageId: String,

    @field:NotBlank(message = "Reaction content must not be blank")
    @field:Size(max = 10, message = "Reaction content too long (max 10)")
    val content: String,
)

data class AudioMessageRequest(
    @field:Size(max = 24, message = "Message ID too long")
    val messageId: String?, //Objectid

    @field:NotBlank(message = "Receiver ID must not be blank")
    @field:Size(max = 24, message = "Receiver ID too long")
    val receiverId: String,
    val groupMessage: Boolean,
    val msgType: MessageType,
    //val content: String,
    @field:Size(max = 24, message = "Answer ID too long")
    val answerId: String?,
    @field:NotBlank(message = "Client message ID must not be blank")
    @field:Size(max = 36, message = "Client message ID too long")
    val clientMessageId: String,
)
