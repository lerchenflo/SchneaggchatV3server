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
import com.lerchenflo.schneaggchatv3server.notifications.push.DeviceDirectory
import com.lerchenflo.schneaggchatv3server.notifications.push.PushDispatcher
import com.lerchenflo.schneaggchatv3server.notifications.push.PushOptions
import com.lerchenflo.schneaggchatv3server.notifications.push.PushProvider
import com.lerchenflo.schneaggchatv3server.notifications.websocket.SocketConnectionHandler
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
 * Notificationservice to notify the user about updates. if the user is connected via socket connection, send
 * via socket, else via firebase. not all updates are sent via firebase (Message updates etc do not matter if the
 * client is offline, he will sync on app start)
 */
@Service
class NotificationService(
    private val socketConnectionHandler: SocketConnectionHandler,
    private val deviceDirectory: DeviceDirectory,
    private val pushDispatcher: PushDispatcher,

    private val userLookupService: UserLookupService,
    private val groupLookupService: GroupLookupService,
    private val friendsLookupService: FriendsLookupService,
) {

    
    /**
     * Send a notification to a client device
     * @param message the message which changed
     * @param newMessage is this a new message
     * @param deleted did this message just get deleted
     * @param changingUserId user which made the change
     * @return the recipients the socket send actually reached. [notifyReactionAdded] decides its
     * own push fallback from this instead of doing a second, independent presence lookup that can
     * disagree with what happened here (a session reaped in between, a send that failed). Callers
     * that only need the fan-out can ignore it.
     */
    fun  notifyMessageUpdate(message: Message, newMessage: Boolean, deleted: Boolean, changingUserId: ObjectId): Set<ObjectId> {

        val deliveredOverSocket = mutableSetOf<ObjectId>()

        if (message.groupMessage) {
            val groupName = groupLookupService.getGroupById(message.receiverId)?.name
                ?: "Unknown Group"

            groupLookupService.getGroupMembers(message.receiverId).forEach { member ->
                //Exclude the changing user
                if (member.userid == changingUserId) return@forEach

                if (deliverMessageChange(message, member.userid, groupName, newMessage, deleted)) {
                    deliveredOverSocket += member.userid
                }
            }
        } else {
            //Notify the user which did not change the message
            val recipient = if (message.senderId == changingUserId) message.receiverId else message.senderId

            if (deliverMessageChange(message, recipient, groupName = null, newMessage, deleted)) {
                deliveredOverSocket += recipient
            }
        }

        return deliveredOverSocket
    }


    /** @return whether the socket took this over for [recipient] */
    private fun deliverMessageChange(message: Message, recipient: ObjectId, groupName: String?, newMessage: Boolean, deleted: Boolean): Boolean {
        val socketMessage = SocketConnectionMessage.MessageChange(
            message = message.toMessageResponse(recipient),
            deleted = deleted,
            newMessage = newMessage,
        )

        if (!newMessage) return socketConnectionHandler.sendMessage(socketMessage, receiverId = recipient)

        val pushNewMessage = { pushToUser(recipient, newMessageNotification(message, recipient, groupName)) }

        //Socket first; the push is held until the client acks, or sent right away if there is no socket
        val tookOver = socketConnectionHandler.sendNewMessage(socketMessage, recipient, message.id.toHexString(), pushNewMessage)
        if (!tookOver) pushNewMessage()
        return tookOver
    }

    private fun newMessageNotification(message: Message, recipient: ObjectId, groupName: String?) =
        NotificationResponse.MessageNotificationResponse(
            msgId = message.id.toHexString(),
            senderName = userLookupService.getUsername(message.senderId),
            messageType = message.msgType,
            groupMessage = message.groupMessage,
            groupName = groupName ?: "",
            content = message.content,
            senderId = message.senderId.toHexString(),
            receiverId = recipient.toHexString(),
            groupId = if (message.groupMessage) message.receiverId.toHexString() else "",
            sendDate = message.sendDate.toEpochMilliseconds(),
            answerId = message.answerId?.toHexString() ?: "",
        )

    private fun pushToUser(userId: ObjectId, notification: NotificationResponse, options: PushOptions = PushOptions()) {
        pushDispatcher.push(deviceDirectory.targetsOf(userId), notification, options)
    }

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

        recipients.distinct().forEach { recipientId ->
            socketConnectionHandler.sendMessage(
                message = SocketConnectionMessage.MessageChange(
                    message = message.toMessageResponse(recipientId),
                    newMessage = true,
                    deleted = false,
                ),
                receiverId = recipientId,
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
            socketConnectionHandler.sendMessage(
                SocketConnectionMessage.UserChange(
                    user = user.toUserResponseFor(interaction),
                    deleted = deleted
                ),
                receiverId = counterpartId
            )
        }
    }

    /**
     * Push [user]'s own row ([UserResponse.SelfUserResponse]) to every device of [user] only - for
     * changes no friend sees, like the derived `locationShared` flag.
     */
    fun notifyOwnUserUpdate(user: User, deleted: Boolean = false) {
        socketConnectionHandler.sendMessage(
            SocketConnectionMessage.UserChange(
                user = user.toSelfUserResponse(
                    locationShared = friendsLookupService.hasActiveLocationSharing(user.id),
                ),
                deleted = deleted
            ),
            receiverId = user.id
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

        socketConnectionHandler.sendMessage(socketMessage, receiverId = recipientId)
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

        friendsLookupService.getFriends(userId).forEach { friendId ->
            socketConnectionHandler.sendMessage(message, friendId)
        }
    }

    /**
     * Push the connecting user a one-time snapshot of which of their friends are currently online.
     */
    fun sendInitialFriendOnlineSnapshot(userId: ObjectId) {
        val onlineFriendIds = friendsLookupService.getFriends(userId)
            .filter { socketConnectionHandler.isConnected(it) }
            .map { it.toHexString() }

        socketConnectionHandler.sendMessage(
            SocketConnectionMessage.FriendOnlineStatusSnapshot(onlineFriendIds),
            receiverId = userId
        )
    }

    /**
     * @param removedMemberIds users who just left or got removed. They are no longer in
     * [GroupResponse.members], but every device of theirs has to drop the group (and its messages),
     * so they get the group as deleted.
     */
    fun notifyGroupUpdate(groupResponse: GroupResponse, deleted: Boolean, removedMemberIds: Collection<ObjectId> = emptyList()) {

        //Only for socket connected users
        groupResponse.members.forEach { member ->
            socketConnectionHandler.sendMessage(
                SocketConnectionMessage.GroupChange(
                    group = groupResponse,
                    deleted = deleted
                ),
                receiverId = ObjectId(member.userid),
            )
        }

        removedMemberIds.forEach { removedMemberId ->
            socketConnectionHandler.sendMessage(
                SocketConnectionMessage.GroupChange(
                    group = groupResponse,
                    deleted = true
                ),
                receiverId = removedMemberId,
            )
        }
    }

    /**
     * Send a changed group extension to every member of its group, the writer included (a second
     * device of the writer needs it too). Socket only - offline members catch up with
     * /groupextensions/sync.
     */
    fun notifyGroupExtensionUpdate(extension: GroupExtension, deleted: Boolean) {
        val response = extension.toGroupExtensionResponse()
        groupLookupService.getGroupMembers(extension.ownerId).forEach { member ->
            socketConnectionHandler.sendMessage(
                SocketConnectionMessage.GroupExtensionChange(
                    extension = response,
                    deleted = deleted
                ),
                receiverId = member.userid,
            )
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

        toNotify.forEach { user ->
            val userOid = ObjectId(user)
            val delivered = socketConnectionHandler.sendMessage(socketMessage, receiverId = userOid)

            // Push fallback for whoever the socket could not reach, decided by that same send
            // rather than a second presence lookup. The creator is skipped - they already know.
            if (notification != null && !delivered && user != eventResponse.creatorId) {
                pushToUser(userOid, notification)
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
        pushToUser(recipientId, notification)
    }

    /** Wake is Android only, and a wake that arrives late is worse than none, so TTL 0. */
    fun notifyWake(recipientId: ObjectId, notification: NotificationResponse.WakeNotificationResponse) {
        pushToUser(recipientId, notification, PushOptions(ttlSeconds = 0, providers = setOf(PushProvider.FCM)))
    }

    /** Devices a wake can reach. The count is shown to the sender and sent in the wake payload. */
    fun wakeableDeviceCount(userId: ObjectId): Int =
        deviceDirectory.targetsOf(userId).count { it.provider == PushProvider.FCM }

    /**
     * Notify the original message sender that someone added a reaction.
     * Only fires on add (not on remove). Skipped if the reactor is the message sender.
     * The recipient is always the message sender; other group members are not notified.
     * Live UI sync (WebSocket MessageChange) is already handled by [notifyMessageUpdate];
     * this only takes care of the push fallback when the recipient is offline.
     *
     * @param deliveredOverSocket what that [notifyMessageUpdate] call actually reached - pass its
     * return value. Deciding the fallback from the real send result instead of a fresh
     * `isConnected` check closes the window where a session is still registered but already dead
     * (it stays in the registry until the keepalive sweep reaps it), which used to drop the push
     * entirely without ever attempting a delivery that could fail.
     */
    fun notifyReactionAdded(
        message: Message,
        reactorId: ObjectId,
        reactionContent: String,
        deliveredOverSocket: Set<ObjectId>,
    ) {
        if (message.senderId == reactorId) return

        val recipient = message.senderId

        if (recipient in deliveredOverSocket) return

        val groupName = if (message.groupMessage) {
            groupLookupService.getGroupById(message.receiverId)?.name ?: "Unknown Group"
        } else ""

        pushToUser(
            recipient,
            NotificationResponse.MessageNotificationResponse(
                msgId = message.id.toHexString(),
                senderName = userLookupService.getUsername(reactorId),
                messageType = message.msgType,
                groupMessage = message.groupMessage,
                groupName = groupName,
                content = reactionContent,
                senderId = reactorId.toHexString(),
                receiverId = recipient.toHexString(),
                reaction = true,
                groupId = if (message.groupMessage) message.receiverId.toHexString() else "",
            ),
        )
    }

    fun notifyMapUpdate(entry: MapEntry, newEntry: Boolean, deleted: Boolean, excludeUserId: ObjectId?) {
        socketConnectionHandler.broadcast(
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
    fun isUserConnected(userId: ObjectId): Boolean = socketConnectionHandler.isConnected(userId)

    /** Push one friend's live position to [recipientId] (~every 5s; no snail trail, no FCM fallback). */
    fun notifyFriendLocationChange(recipientId: ObjectId, friend: FriendLocationPayload) {
        socketConnectionHandler.sendMessage(
            SocketConnectionMessage.FriendLocationChange(friend = friend),
            receiverId = recipientId,
        )
    }

    /** Push the full set of currently-visible friend positions + trails to [recipientId] (initial load). */
    fun notifyLocationSnapshot(recipientId: ObjectId, friends: List<FriendLocationSnapshot>) {
        socketConnectionHandler.sendMessage(
            SocketConnectionMessage.FriendLocationsSnapshot(friends = friends),
            receiverId = recipientId,
        )
    }

    /** Push one new snail-trail point of [ownerUserId] to [recipientId] (~once/minute when moving). */
    fun notifySnailTrailPointAdded(recipientId: ObjectId, ownerUserId: String, point: SnailTrailPointPayload) {
        socketConnectionHandler.sendMessage(
            SocketConnectionMessage.SnailTrailPointAdded(userId = ownerUserId, point = point),
            receiverId = recipientId,
        )
    }

    fun notifyFriendRequest(requestingUser: ObjectId, receivingUser: ObjectId, accepted: Boolean) {
        val requestingUserName = userLookupService.getUsername(requestingUser)

        val delivered = socketConnectionHandler.sendMessage(
            SocketConnectionMessage.FriendRequest(
                requestingUser = requestingUser.toHexString(),
                requestingUserName = requestingUserName,
                accepted = accepted,
            ),
            receiverId = receivingUser,
        )

        if (!delivered) {
            pushToUser(
                receivingUser,
                NotificationResponse.FriendRequestNotificationResponse(
                    requesterId = requestingUser.toHexString(),
                    requesterName = requestingUserName,
                    accepted = accepted,
                ),
            )
        }
    }

}
