package com.lerchenflo.schneaggchatv3server.message.system

import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.message.MessageLookupService
import com.lerchenflo.schneaggchatv3server.message.messagemodel.*
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.SyncCollection
import com.lerchenflo.schneaggchatv3server.util.VersionCounterService
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import kotlin.time.Clock

/**
 * Writes server-authored `MessageType.SYSTEM` messages for group changes, friend-request
 * acceptance and wakes - the in-chat equivalent of WhatsApp's "X joined the group" lines.
 *
 * Deliberately does NOT depend on [com.lerchenflo.schneaggchatv3server.message.MessageService] -
 * that service already injects `FriendsService`, so injecting it here (needed to call this from
 * `FriendsService.acceptFriendRequest`) would create a circular Spring bean dependency.
 *
 * Sender convention - see the KDoc on [Message.senderId]:
 * - group events: `senderId = receiverId = groupId`. The actor is recorded only in
 *   [SystemEvent.actorId], never as the message sender - this keeps a user who leaves/is kicked
 *   from continuing to sync their own system messages via the `senderId == user` visibility
 *   clause, and keeps these out of `RecapService`'s sent-message statistics.
 * - direct events (friendship accepted, 1:1 wake): `senderId`/`receiverId` are the two real user
 *   ids - required for DM sync visibility, which has no group clause to fall back on.
 *
 * A write failure here must never break the underlying group/friend/wake action, so every public
 * method swallows its own exceptions after logging them.
 */
@Component
class SystemMessageService(
    private val messageLookupService: MessageLookupService,
    private val versionCounterService: VersionCounterService,
    private val notificationService: NotificationService,
    private val userLookupService: UserLookupService,
    private val groupLookupService: GroupLookupService,
) {

    /**
     * Write a group system message. `senderId = receiverId = groupId` (see class doc); readers
     * are seeded with every current member plus [targets], so nobody ever sees an unread badge
     * for a system event.
     */
    fun groupEvent(
        groupId: ObjectId,
        eventType: SystemEventType,
        actorId: ObjectId,
        targets: List<ObjectId> = emptyList(),
        text: String? = null,
        previousText: String? = null,
    ) {
        runCatching {
            val distinctTargets = targets.distinct()
            val event = SystemEvent(
                eventType = eventType,
                actorId = actorId,
                actorName = userLookupService.getUsername(actorId),
                targets = distinctTargets.map { SystemEventParticipant(it, userLookupService.getUsername(it)) },
                text = text,
                previousText = previousText,
            )

            val readerIds = (groupLookupService.getGroupMembers(groupId).map { it.userid } + distinctTargets).distinct()

            emit(
                receiverId = groupId,
                senderId = groupId,
                groupMessage = true,
                event = event,
                readerIds = readerIds,
            )
        }.onFailure { e ->
            AppLogger.error("Failed to write group system message ($eventType) for group $groupId: ${e.message}")
        }
    }

    /**
     * Write the "you are now friends" message into the DM chat, once a friend request is
     * accepted. `senderId = acceptingUserId`, `receiverId = requesterId` - see class doc for why
     * a direct event needs real user ids rather than the group convention.
     */
    fun friendshipAccepted(acceptingUserId: ObjectId, requesterId: ObjectId) {
        runCatching {
            val event = SystemEvent(
                eventType = SystemEventType.FRIENDSHIP_ACCEPTED,
                actorId = acceptingUserId,
                actorName = userLookupService.getUsername(acceptingUserId),
                targets = listOf(SystemEventParticipant(requesterId, userLookupService.getUsername(requesterId))),
            )

            emit(
                receiverId = requesterId,
                senderId = acceptingUserId,
                groupMessage = false,
                event = event,
                readerIds = listOf(acceptingUserId, requesterId),
            )
        }.onFailure { e ->
            AppLogger.error("Failed to write friendship-accepted system message ($acceptingUserId <-> $requesterId): ${e.message}")
        }
    }

    /** Write a "X woke you up" message into the DM chat a 1:1 wake was sent to. */
    fun wakeSentToUser(senderId: ObjectId, targetId: ObjectId, reason: String) {
        runCatching {
            val event = SystemEvent(
                eventType = SystemEventType.WAKE_SENT,
                actorId = senderId,
                actorName = userLookupService.getUsername(senderId),
                targets = listOf(SystemEventParticipant(targetId, userLookupService.getUsername(targetId))),
                text = reason,
            )

            emit(
                receiverId = targetId,
                senderId = senderId,
                groupMessage = false,
                event = event,
                readerIds = listOf(senderId, targetId),
            )
        }.onFailure { e ->
            AppLogger.error("Failed to write wake system message ($senderId -> $targetId): ${e.message}")
        }
    }

    /** Write a "X woke the group up" message into the group chat a group wake was sent to. */
    fun wakeSentToGroup(groupId: ObjectId, actorId: ObjectId, reason: String) {
        groupEvent(
            groupId = groupId,
            eventType = SystemEventType.WAKE_SENT,
            actorId = actorId,
            text = reason,
        )
    }

    private fun emit(
        receiverId: ObjectId,
        senderId: ObjectId,
        groupMessage: Boolean,
        event: SystemEvent,
        readerIds: Collection<ObjectId>,
    ) {
        val now = Clock.System.now()

        val message = versionCounterService.withVersion(SyncCollection.MESSAGES) { version ->
            messageLookupService.saveMessage(
                Message(
                    senderId = senderId,
                    receiverId = receiverId,
                    groupMessage = groupMessage,
                    msgType = MessageType.SYSTEM,
                    answerId = null,
                    content = "",
                    systemEvent = event,
                    sendDate = now,
                    lastChanged = now,
                    deleted = false,
                    version = version,
                    readers = readerIds.distinct().map { Reader(userId = it, readAt = now) },
                )
            )
        }

        notificationService.notifySystemMessage(message)
    }
}
