package com.lerchenflo.schneaggchatv3server.message.messagemodel

data class MessageRequest(
    val messageId: String?, //Objectid

    val receiverId: String,
    val groupMessage: Boolean,
    val msgType: MessageType,
    val content: String,
    val answerId: String?
)

data class PollMessageRequest(
    val receiverId: String,
    val groupMessage: Boolean,
    val msgType: MessageType,
    val answerId: String?,
    val poll: PollCreateRequest
)

data class ImageMessageRequest(
    val messageId: String?, //Objectid

    val receiverId: String,
    val groupMessage: Boolean,
    val msgType: MessageType,
    val content: String,
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
