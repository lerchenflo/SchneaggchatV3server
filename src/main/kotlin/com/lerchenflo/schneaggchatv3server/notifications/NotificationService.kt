package com.lerchenflo.schneaggchatv3server.notifications

import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventVisibility
import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.group.groupextensions.model.GroupExtension
import com.lerchenflo.schneaggchatv3server.group.groupextensions.model.toGroupExtensionResponse
import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse
import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.message.messagemodel.toMessageResponse
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.notifications.push.PushOptions
import com.lerchenflo.schneaggchatv3server.notifications.push.PushProvider
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnectionMessage
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntry
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.toMapEntryResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.FriendLocationPayload
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.FriendLocationSnapshot
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.SnailTrailPointPayload
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.user.usermodel.toSelfUserResponse
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserResponse
import com.lerchenflo.schneaggchatv3server.user.usermodel.toUserResponseFor
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import kotlin.time.ExperimentalTime
import kotlin.time.Instant


/**
 * Works out who gets told about a change and what they get. [DeliveryService] then gets it to their
 * devices. Not every update has a push: message edits etc. do not matter to an offline client, it
 * syncs on app start.
 */
@Service
class NotificationService(
    private val deliveryService: DeliveryService,

    private val userLookupService: UserLookupService,
    private val groupLookupService: GroupLookupService,
    private val friendsLookupService: FriendsLookupService,
) {

    
    /**
     * Tell the participants of a message about a change, except the user who made it.
     * @param newMessage is this a new message
     * @param deleted did this message just get deleted
     * @param changingUserId user which made the change
     * @param addedReaction the reaction this change added. The message sender gets a push for it,
     * everybody else only the live update.
     */
    fun notifyMessageUpdate(message: Message, newMessage: Boolean, deleted: Boolean, changingUserId: ObjectId, addedReaction: String? = null) {
        val recipients = if (message.groupMessage) {
            groupLookupService.getGroupMembers(message.receiverId).map { it.userid }.filter { it != changingUserId }
        } else {
            listOf(if (message.senderId == changingUserId) message.receiverId else message.senderId)
        }

        val push = when {
            newMessage -> newMessageNotification(message)
            addedReaction != null -> reactionNotification(message, changingUserId, addedReaction)
            else -> null
        }

        deliveryService.sendAll(recipients) { recipient ->
            val socket = SocketConnectionMessage.MessageChange(
                message = message.toMessageResponse(recipient),
                deleted = deleted,
                newMessage = newMessage,
            )

            when {
                push == null -> Delivery.Live(socket)
                newMessage -> Delivery.Alert(socket, push.copy(receiverId = recipient.toHexString()), id = message.id.toHexString(), awaitAck = true)
                recipient == message.senderId -> Delivery.Alert(socket, push.copy(receiverId = recipient.toHexString()))
                else -> Delivery.Live(socket)
            }
        }
    }

    private fun newMessageNotification(message: Message) = NotificationResponse.MessageNotificationResponse(
        msgId = message.id.toHexString(),
        senderName = userLookupService.getUsername(message.senderId),
        messageType = message.msgType,
        groupMessage = message.groupMessage,
        groupName = groupNameOf(message),
        content = message.content,
        senderId = message.senderId.toHexString(),
        receiverId = "", // set per recipient
        groupId = if (message.groupMessage) message.receiverId.toHexString() else "",
        sendDate = message.sendDate.toEpochMilliseconds(),
        answerId = message.answerId?.toHexString() ?: "",
    )

    private fun reactionNotification(message: Message, reactorId: ObjectId, reaction: String) = NotificationResponse.MessageNotificationResponse(
        msgId = message.id.toHexString(),
        senderName = userLookupService.getUsername(reactorId),
        messageType = message.msgType,
        groupMessage = message.groupMessage,
        groupName = groupNameOf(message),
        content = reaction,
        senderId = reactorId.toHexString(),
        receiverId = "", // set per recipient
        reaction = true,
        groupId = if (message.groupMessage) message.receiverId.toHexString() else "",
    )

    private fun groupNameOf(message: Message): String =
        if (message.groupMessage) groupLookupService.getGroupById(message.receiverId)?.name ?: "Unknown Group" else ""

    /**
     * Push a server-authored `MessageType.SYSTEM` message (group change, friend accepted, wake -
     * see [com.lerchenflo.schneaggchatv3server.message.system.SystemMessageService]) live over the
     * socket. Socket-only, no FCM/APNs fallback - offline clients pick it up on their next
     * `/messages/sync`, exactly like [notifyGroupUpdate] and [notifyMapUpdate].
     *
     * Unlike [notifyMessageUpdate], this fans out to **every** participant including the actor:
     * a system message has no HTTP response body to carry it back to whoever performed the
     * action, so their own devices only learn about it here.
     */
    fun notifySystemMessage(message: Message) {
        val recipients = if (message.groupMessage) {
            groupLookupService.getGroupMembers(message.receiverId).map { it.userid }
        } else {
            listOf(message.senderId, message.receiverId)
        }

        deliveryService.sendAll(recipients.distinct()) { recipientId ->
            Delivery.Live(
                SocketConnectionMessage.MessageChange(
                    message = message.toMessageResponse(recipientId),
                    newMessage = true,
                    deleted = false,
                )
            )
        }
    }

    /**
     * Push a change of [user] to every device of the user themselves and of everyone with a
     * friendship row towards them, each in the shape `/users/sync` would give them: friends get the
     * friend row with their own settings towards [user], pending / blocked counterparts the simple
     * row (it carries the username and the picture timestamp too).
     */
    fun notifyUserUpdate(user: User, deleted: Boolean) {
        notifyOwnUserUpdate(user, deleted)

        friendsLookupService.getCounterpartInteractions(user.id).forEach { (counterpartId, interaction) ->
            deliveryService.send(
                counterpartId,
                Delivery.Live(
                    SocketConnectionMessage.UserChange(
                        user = user.toUserResponseFor(interaction),
                        deleted = deleted
                    )
                ),
            )
        }
    }

    /**
     * Push [user]'s own row ([UserResponse.SelfUserResponse]) to every device of [user] only - for
     * changes no friend sees, like the derived `locationShared` flag.
     */
    fun notifyOwnUserUpdate(user: User, deleted: Boolean = false) {
        deliveryService.send(
            user.id,
            Delivery.Live(
                SocketConnectionMessage.UserChange(
                    user = user.toSelfUserResponse(
                        locationShared = friendsLookupService.hasActiveLocationSharing(user.id),
                    ),
                    deleted = deleted
                )
            ),
        )
    }

    /**
     * Push [recipientId]'s current view of [otherUserId] to every device of [recipientId], read
     * from the database the way `/users/sync` builds it: the friend row for an accepted friendship,
     * the simple row for a pending one, and a deleted [SocketConnectionMessage.UserChange] once no
     * friendship row is left (request declined, friend removed).
     *
     * Call it for each side whose view changed after every friendship mutation - the acting user
     * included, because the client no longer syncs after its own change and its other devices
     * would not learn about it at all.
     */
    fun notifyRelationChange(recipientId: ObjectId, otherUserId: ObjectId) {
        val interaction = friendsLookupService.getInteraction(viewerId = recipientId, otherUserId = otherUserId)

        val socketMessage = if (interaction == null) {
            SocketConnectionMessage.UserChange(
                //No relationship left, so no user data either: the client only reads the id of a
                //deleted user. requesterId must still be non-null - the client declares it so and
                //would drop the whole message as unparsable.
                user = UserResponse.SimpleUserResponse(
                    id = otherUserId.toHexString(),
                    username = "",
                    updatedAt = 0,
                    profilePicUpdatedAt = 0,
                    friendShipStatus = null,
                    requesterId = recipientId.toHexString(),
                ),
                deleted = true
            )
        } else {
            val otherUser = userLookupService.findById(otherUserId) ?: return
            SocketConnectionMessage.UserChange(
                user = otherUser.toUserResponseFor(interaction),
                deleted = false
            )
        }

        deliveryService.send(recipientId, Delivery.Live(socketMessage))
    }

    /**
     * Fan out a friend's online/offline status edge to all of their connected friends.
     * No FCM/APNs fallback - this is a live-only event, offline friends catch up via `/sync`'s lastSeen field.
     * @param userId the user whose connection state just changed
     * @param online true if this was their first connection, false if their last connection just closed
     * @param lastSeen only populated when going offline
     */
    @OptIn(ExperimentalTime::class)
    fun notifyFriendOnlineStatusChange(userId: ObjectId, online: Boolean, lastSeen: Instant? = null) {
        val message = SocketConnectionMessage.FriendOnlineStatusChange(
            userId = userId.toHexString(),
            online = online,
            lastSeen = lastSeen?.toEpochMilliseconds()
        )

        deliveryService.sendAll(friendsLookupService.getFriends(userId)) { Delivery.Live(message) }
    }

    /**
     * Push the session that just connected a one-time snapshot of which friends are currently online.
     */
    fun sendInitialFriendOnlineSnapshot(userId: ObjectId, sessionId: String) {
        val onlineFriendIds = friendsLookupService.getFriends(userId)
            .filter { deliveryService.isConnected(it) }
            .map { it.toHexString() }

        deliveryService.sendToSession(sessionId, SocketConnectionMessage.FriendOnlineStatusSnapshot(onlineFriendIds))
    }

    /**
     * @param removedMemberIds users who just left or got removed. They are no longer in
     * [GroupResponse.members], but every device of theirs has to drop the group (and its messages),
     * so they get the group as deleted.
     */
    fun notifyGroupUpdate(groupResponse: GroupResponse, deleted: Boolean, removedMemberIds: Collection<ObjectId> = emptyList()) {

        val members = groupResponse.members.map { ObjectId(it.userid) }

        //Only for socket connected users
        deliveryService.sendAll(members) { Delivery.Live(SocketConnectionMessage.GroupChange(group = groupResponse, deleted = deleted)) }
        deliveryService.sendAll(removedMemberIds) { Delivery.Live(SocketConnectionMessage.GroupChange(group = groupResponse, deleted = true)) }
    }

    /**
     * Send a changed group extension to every member of its group, the writer included (a second
     * device of the writer needs it too). Socket only - offline members catch up with
     * /groupextensions/sync.
     */
    fun notifyGroupExtensionUpdate(extension: GroupExtension, deleted: Boolean) {
        val response = extension.toGroupExtensionResponse()
        deliveryService.sendAll(groupLookupService.getGroupMembers(extension.ownerId).map { it.userid }) {
            Delivery.Live(SocketConnectionMessage.GroupExtensionChange(extension = response, deleted = deleted))
        }
    }

    fun notifyEventUpdate(eventResponse: EventResponse, newEntry: Boolean, deleted: Boolean) {

        val audience = when (eventResponse.visibility) {
            // Only explicitly invited users can see this event
            EventVisibility.INVITED_FRIENDS_ONLY -> eventResponse.invitedUsers
            // Everyone who can access the event (invited users + the creator's friends) gets notified
            EventVisibility.FRIENDS_ONLY, EventVisibility.PUBLIC ->
                eventResponse.invitedUsers + friendsLookupService.getFriends(ObjectId(eventResponse.creatorId)).map { it.toHexString() }
        }

        // The creator is in neither list but has to see the event change too - somebody else
        // responding to their event is a change they never triggered themselves.
        val toNotify = (audience + eventResponse.creatorId).toSet()

        val socketMessage = SocketConnectionMessage.EventChange(
            event = eventResponse,
            newEntry = newEntry,
            deleted = deleted
        )

        // Firebase + APNs push for brand-new events only
        val notification = if (newEntry) {
            NotificationResponse.EventNotificationResponse(
                eventId = eventResponse.id,
                eventTitle = eventResponse.title,
                creatorId = eventResponse.creatorId,
                creatorName = eventResponse.creatorName,
            )
        } else null

        deliveryService.sendAll(toNotify.map { ObjectId(it) }) { user ->
            // Push for whoever the socket can not reach. The creator is skipped - they already know.
            if (notification != null && user.toHexString() != eventResponse.creatorId) {
                Delivery.Alert(socketMessage, notification)
            } else {
                Delivery.Live(socketMessage)
            }
        }
    }

    fun notifyBirthday(birthdayUserId: ObjectId, recipientId: ObjectId, ownBirthday: Boolean) {
        val name = userLookupService.getUsername(birthdayUserId)
        val notification = NotificationResponse.BirthdayNotificationResponse(
            birthdayUserId = birthdayUserId.toHexString(),
            birthdayUserName = name,
            ownBirthday = ownBirthday,
        )
        deliveryService.send(recipientId, Delivery.PushOnly(notification))
    }

    /** Wake is Android only, and a wake that arrives late is worse than none, so TTL 0. */
    fun notifyWake(recipientId: ObjectId, notification: NotificationResponse.WakeNotificationResponse) {
        deliveryService.send(
            recipientId,
            Delivery.PushOnly(notification, PushOptions(ttlSeconds = 0, providers = setOf(PushProvider.FCM))),
        )
    }

    /** Devices a wake can reach. The count is shown to the sender and sent in the wake payload. */
    fun wakeableDeviceCount(userId: ObjectId): Int = deliveryService.pushDeviceCount(userId, PushProvider.FCM)

    fun notifyMapUpdate(entry: MapEntry, newEntry: Boolean, deleted: Boolean, excludeUserId: ObjectId?) {
        deliveryService.broadcast(
            SocketConnectionMessage.MapChange(
                mapEntry = entry.toMapEntryResponse(
                    updatedByName = userLookupService.findById(entry.updatedBy)?.username ?: "Unknown"
                ),
                newEntry = newEntry,
                deleted = deleted,
            ),
            excludeUserId = excludeUserId //Do not exclude the editor, he also needs the update
        )
    }

    /** Is the user currently connected via a WebSocket? Lets callers skip work for offline users. */
    fun isUserConnected(userId: ObjectId): Boolean = deliveryService.isConnected(userId)

    /** Push one friend's live position to [recipientId] (~every 5s; no snail trail, no FCM fallback). */
    fun notifyFriendLocationChange(recipientId: ObjectId, friend: FriendLocationPayload) {
        deliveryService.send(recipientId, Delivery.Live(SocketConnectionMessage.FriendLocationChange(friend = friend)))
    }

    /** Push the full set of currently-visible friend positions + trails to one session (initial load). */
    fun notifyLocationSnapshot(sessionId: String, friends: List<FriendLocationSnapshot>) {
        deliveryService.sendToSession(sessionId, SocketConnectionMessage.FriendLocationsSnapshot(friends = friends))
    }

    /** Push one new snail-trail point of [ownerUserId] to [recipientId] (~once/minute when moving). */
    fun notifySnailTrailPointAdded(recipientId: ObjectId, ownerUserId: String, point: SnailTrailPointPayload) {
        deliveryService.send(recipientId, Delivery.Live(SocketConnectionMessage.SnailTrailPointAdded(userId = ownerUserId, point = point)))
    }

    fun notifyFriendRequest(requestingUser: ObjectId, receivingUser: ObjectId, accepted: Boolean) {
        val requestingUserName = userLookupService.getUsername(requestingUser)

        deliveryService.send(
            receivingUser,
            Delivery.Alert(
                socket = SocketConnectionMessage.FriendRequest(
                    requestingUser = requestingUser.toHexString(),
                    requestingUserName = requestingUserName,
                    accepted = accepted,
                ),
                push = NotificationResponse.FriendRequestNotificationResponse(
                    requesterId = requestingUser.toHexString(),
                    requesterName = requestingUserName,
                    accepted = accepted,
                ),
            ),
        )
    }

}
