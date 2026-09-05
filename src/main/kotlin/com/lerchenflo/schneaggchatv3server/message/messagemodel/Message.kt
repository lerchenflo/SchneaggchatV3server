@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.message.messagemodel

import org.bson.types.ObjectId
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@TypeAlias("message")
@Document("messages")
@CompoundIndexes(
    // Backs the version-sync range scan: sender/receiver visibility filter + `version > since`.
    CompoundIndex(name = "senderId_version_idx", def = "{'senderId': 1, 'version': 1}"),
    CompoundIndex(name = "receiverId_version_idx", def = "{'receiverId': 1, 'version': 1}"),
    // Send idempotency: a retried send (offline queue, lost response, replayed auth-refresh
    // request) reuses the client's original `clientMessageId` and must resolve to the SAME
    // document instead of creating a duplicate. Partial on {'$type': 'string'} so SYSTEM
    // messages (never keyed, and `senderId` there is a group id, not a user id - see below)
    // and plain nulls don't collide with each other.
    CompoundIndex(
        name = "sender_client_message_idx",
        def = "{'senderId': 1, 'clientMessageId': 1}",
        unique = true,
        partialFilter = "{'clientMessageId': {'\$type': 'string'}}"
    ),
)
data class Message(
    val id: ObjectId = ObjectId.get(),

    /**
     * For `msgType == SYSTEM && groupMessage`, this is the **group id**, not a user id - the group
     * "speaks for itself" so sync visibility, delete permission and
     * [com.lerchenflo.schneaggchatv3server.user.recap.RecapService] sent-message stats aren't affected
     * by who performed the action (see [com.lerchenflo.schneaggchatv3server.message.system.SystemMessageService]).
     * The actor is recorded in `systemEvent.actorId` instead. Every other message (including
     * direct SYSTEM messages) has a real user id here.
     */
    @Indexed
    val senderId: ObjectId,
    @Indexed
    val receiverId: ObjectId,
    val groupMessage: Boolean,
    val msgType: MessageType,
    val answerId: ObjectId?,

    val content: String,
    val poll: PollMessage? = null,
    val systemEvent: SystemEvent? = null,

    val sendDate: Instant,
    val lastChanged: Instant,

    val deleted: Boolean,

    val edited: Boolean = false,

    /**
     * Monotonic per-collection version stamped by [com.lerchenflo.schneaggchatv3server.util.VersionCounterService]
     * on every write (send/edit/react/vote/delete/read-receipt). This - not [lastChanged] - is the
     * cursor `/messages/sync` filters and paginates on. Defaults to 0 for documents written before
     * this field existed; backfilled by `MainController.migrateMessageVersions()`. Not indexed on
     * its own - every sync query filters by sender/receiver first, so the compound indexes above
     * cover it; a bare index here would be redundant overhead on every write.
     */
    val version: Long = 0,

    val readers: List<Reader>,

    val reactions: List<Reaction> = emptyList(),

    /**
     * Client-generated idempotency key for a send (not for edit/react/vote/delete). Stable
     * across retries of the same logical message - enforced unique per sender by the
     * `sender_client_message_idx` partial index above. Never echoed back in [MessageResponse];
     * inbound-only.
     */
    val clientMessageId: String? = null,
)

data class Reader(
    val userId: ObjectId,
    val readAt: Instant
)

data class Reaction(
    val userId: ObjectId,
    val content: String,
    val reactedAt: Instant,
)

enum class MessageType {
    TEXT,
    IMAGE,
    POLL,
    AUDIO,
    SYSTEM
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
        systemEventResponse = this.systemEvent?.toSystemEventResponse(),

        answerId = this.answerId?.toHexString(),
        sendDate = this.sendDate.toEpochMilliseconds(),
        lastChanged = this.lastChanged.toEpochMilliseconds(),
        deleted = this.deleted,
        version = this.version,
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
        reactedAt = this.reactedAt.toEpochMilliseconds(),
    )
}