package com.lerchenflo.schneaggchatv3server.message.messagemodel

data class MessageResponse(
    val messageId: String, //Objectid
    val senderId: String,
    val receiverId: String,
    val groupMessage: Boolean,
    val msgType: MessageType,
    val content: String,
    val pollResponse: PollResponse?,
    val answerId: String?,

    val sendDate: Long,
    val lastChanged: Long,
    val deleted: Boolean,
    val readers: List<ReaderResponse>,
    val reactions: List<ReactionResponse>,
)


data class ReaderResponse(
    val userId: String,
    val readAt: Long
)

data class ReactionResponse(
    val userId: String,
    val content: String,
    val reactedAt: Long,
)