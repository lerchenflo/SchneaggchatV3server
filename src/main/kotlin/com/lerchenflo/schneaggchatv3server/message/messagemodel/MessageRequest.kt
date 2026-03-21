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
    @field:Size(max = 10000, message = "Content too long")
    val content: String,
    @field:Size(max = 24, message = "Answer ID too long")
    val answerId: String?
)

data class PollMessageRequest(
    @field:NotBlank(message = "Receiver ID must not be blank")
    @field:Size(max = 24, message = "Receiver ID too long")
    val receiverId: String,
    val groupMessage: Boolean,
    val msgType: MessageType,
    @field:Size(max = 24, message = "Answer ID too long")
    val answerId: String?,
    val poll: PollCreateRequest
)

data class ImageMessageRequest(
    @field:Size(max = 24, message = "Message ID too long")
    val messageId: String?, //Objectid

    @field:NotBlank(message = "Receiver ID must not be blank")
    @field:Size(max = 24, message = "Receiver ID too long")
    val receiverId: String,
    val groupMessage: Boolean,
    val msgType: MessageType,
    @field:Size(max = 10000, message = "Content too long")
    val content: String,
    @field:Size(max = 24, message = "Answer ID too long")
    val answerId: String?
)

data class AudioMessageRequest(
    val messageId: String?, //Objectid

    val receiverId: String,
    val groupMessage: Boolean,
    val msgType: MessageType,
    //val content: String,
    val answerId: String?
)
