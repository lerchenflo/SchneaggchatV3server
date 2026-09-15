package com.lerchenflo.schneaggchatv3server.message.messagemodel

data class MessageResponse(
    val messageId: String, //Objectid
    val senderId: String,
    val receiverId: String,
    val groupMessage: Boolean,
    val msgType: MessageType,
    val content: String,
    val pollResponse: PollResponse?,
    val systemEventResponse: SystemEventResponse?,
    val answerId: String?,

    val sendDate: Long,
    val lastChanged: Long,
    val deleted: Boolean,
    val version: Long,
    val readers: List<ReaderResponse>,
    val reactions: List<ReactionResponse>,

    /**
     * Only populated when the requesting user is this message's own sender - see
     * [com.lerchenflo.schneaggchatv3server.message.messagemodel.toMessageResponse]. Lets the
     * sender's own client reconcile a still-in-flight send against a `/messages/sync`/socket
     * pull of the same message that raced ahead of the send's HTTP response, without exposing
     * the key to any other viewer.
     */
    val clientMessageId: String? = null,
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