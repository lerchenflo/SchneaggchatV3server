package com.lerchenflo.schneaggchatv3server.message.messagemodel

import org.bson.types.ObjectId

fun Message.toMessageResponse(requestingUserId: ObjectId) : MessageResponse {
    return MessageResponse(
        messageId = this.id.toHexString(),
        senderId = this.senderId.toHexString(),
        receiverId = this.receiverId.toHexString(),
        groupMessage = this.groupMessage,
        msgType = this.msgType,

        content = this.content,

        pollResponse = this.poll?.toPollMessageResponse(requestingUserId),
        systemEventResponse = this.systemEvent?.toSystemEventResponse(),

        answerId = this.answerId?.toHexString(),
        sendDate = this.sendDate.toEpochMilliseconds(),
        lastChanged = this.lastChanged.toEpochMilliseconds(),
        deleted = this.deleted,
        edited = this.edited,
        version = this.version,
        readers = this.readers.map { it.toReaderResponse() },
        reactions = this.reactions.map { it.toReactionResponse() },
        clientMessageId = if (this.senderId == requestingUserId) this.clientMessageId else null,
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
        reactedAt = this.reactedAt.toEpochMilliseconds(),
    )
}
