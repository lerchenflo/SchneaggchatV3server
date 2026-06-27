@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.message.messagemodel

import org.bson.types.ObjectId
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@TypeAlias("message")
@Document("messages")
data class Message(
    val id: ObjectId = ObjectId.get(),

    @Indexed
    val senderId: ObjectId,
    @Indexed
    val receiverId: ObjectId,
    val groupMessage: Boolean,
    val msgType: MessageType,
    val answerId: ObjectId?,

    val content: String,
    val poll: PollMessage? = null,

    val sendDate: Instant,
    val lastChanged: Instant,

    val deleted: Boolean,

    val edited: Boolean = false,

    val readers: List<Reader>,

    val reactions: List<Reaction> = emptyList(),
)

data class Reader(
    val userId: ObjectId,
    val readAt: Instant
)

data class Reaction(
    val userId: ObjectId,
    val content: String,
)

enum class MessageType {
    TEXT,
    IMAGE,
    POLL,
    AUDIO
}

fun Message.toMessageResponse(requestingUserId: ObjectId) : MessageResponse {
    return MessageResponse(
        messageId = this.id.toHexString(),
        senderId = this.senderId.toHexString(),
        receiverId = this.receiverId.toHexString(),
        groupMessage = this.groupMessage,
        msgType = this.msgType,

        content = this.content,

        pollResponse = this.poll?.toPollMessageResponse(requestingUserId),

        answerId = this.answerId?.toHexString(),
        sendDate = this.sendDate.toEpochMilliseconds(),
        lastChanged = this.lastChanged.toEpochMilliseconds(),
        deleted = this.deleted,
        readers = this.readers.map { it.toReaderResponse() },
        reactions = this.reactions.map { it.toReactionResponse() },
    )
}

fun Reader.toReaderResponse() : ReaderResponse {
    return ReaderResponse(
        userId = this.userId.toHexString(),
        readAt = this.readAt.toEpochMilliseconds()
    )
}

fun Reaction.toReactionResponse() : ReactionResponse {
    return ReactionResponse(
        userId = this.userId.toHexString(),
        content = this.content,
    )
}