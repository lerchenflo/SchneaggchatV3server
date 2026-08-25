package com.lerchenflo.schneaggchatv3server.notifications

import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventResponse
import com.lerchenflo.schneaggchatv3server.events.eventmodel.EventVisibility
import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse
import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.message.messagemodel.toMessageResponse
import com.lerchenflo.schneaggchatv3server.notifications.apns.ApnsService
import com.lerchenflo.schneaggchatv3server.notifications.firebase.FirebaseService
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.notifications.websocket.SocketConnectionHandler
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnectionMessage
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.MapEntry
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.toMapEntryResponse
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.FriendLocationPayload
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.FriendLocationSnapshot
import com.lerchenflo.schneaggchatv3server.schneaggmap.userlocations.model.SnailTrailPointPayload
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsSettingsService
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserResponse
import com.lerchenflo.schneaggchatv3server.user.usermodel.toSelfUserResponse
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
    private val firebaseMessagingService: FirebaseService,
    private val apnsService: ApnsService,

    private val userLookupService: UserLookupService,
    private val groupLookupService: GroupLookupService,
    private val friendsLookupService: FriendsLookupService,
    private val friendsSettingsService: FriendsSettingsService
) {

    
    /**
     * Send a notification to a client device
     * @param message the message which changed
     * @param newMessage is this a new message
     * @param deleted did this message just get deleted
     * @param changingUserId user which made the change
     */
    fun  notifyMessageUpdate(message: Message, newMessage: Boolean, deleted: Boolean, changingUserId: ObjectId) {

        val group = message.groupMessage

        if (group) {
            val groupMembers = groupLookupService.getGroupMembers(message.receiverId)
            val groupName = groupLookupService.getGroupById(message.receiverId)?.name
                ?: "Unknown Group"

            groupMembers.forEach { member ->

                //Exclude the changing user
                if (member.userid == changingUserId) return@forEach

                if (!socketConnectionHandler.sendMessage(
                        message = SocketConnectionMessage.MessageChange(
                            message = message.toMessageResponse(member.userid),
                            deleted = deleted,
                            newMessage = newMessage,
                        ),
                        receiverId = member.userid,
                    )) {

                    if (newMessage) {
                        firebaseMessagingService.sendNewMessageNotificationToUser(
                            senderId = message.senderId,
                            receiverId = member.userid,
                            messageType = message.msgType,
                            messageContent = message.content,
                            msgId = message.id.toHexString(),
                            groupMessage = true,
                            groupName = groupName,
                            groupId = message.receiverId,
                        )
                        apnsService.sendNewMessageNotificationToUser(
                            senderId = message.senderId,
                            receiverId = member.userid,
                            messageType = message.msgType,
                            messageContent = message.content,
                            msgId = message.id.toHexString(),
                            groupMessage = true,
                            groupName = groupName,
                            groupId = message.receiverId,
                        )
                    }
                }

            }

        } else {
            //Single message

            //Try sending via socketconnection
            if (!socketConnectionHandler.sendMessage(
                    SocketConnectionMessage.MessageChange(
                        message = message.toMessageResponse(message.receiverId),
                        deleted = deleted,
                        newMessage = newMessage,
                    ),
                    receiverId = if (message.senderId == changingUserId) message.receiverId else message.senderId, //Notify the user which did not change the message
                )) {

                if (newMessage) {
                    firebaseMessagingService.sendNewMessageNotificationToUser(
                        senderId = message.senderId,
                        receiverId = message.receiverId,
                        messageType = message.msgType,
                        messageContent = message.content,
                        msgId = message.id.toHexString(),
                        groupMessage = false,
                        groupName = null,
                    )
                    apnsService.sendNewMessageNotificationToUser(
                        senderId = message.senderId,
                        receiverId = message.receiverId,
                        messageType = message.msgType,
                        messageContent = message.content,
                        msgId = message.id.toHexString(),
                        groupMessage = false,
                        groupName = null,
                    )
                }

            }
        }



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

    @OptIn(ExperimentalTime::class)
    fun notifyUserUpdate(user: User, deleted: Boolean) {
        // Notify the user themselves
        socketConnectionHandler.sendMessage(
            SocketConnectionMessage.UserChange(
                user = user.toSelfUserResponse(
                    locationShared = friendsLookupService.hasActiveLocationSharing(user.id),
                ),
                deleted = deleted
            ),
            receiverId = user.id
        )

        // Notify friends
        friendsLookupService.getFriendsForUserUpdate(user.id).forEach { (friendId, requesterId, nickName, shareLocation) ->
            socketConnectionHandler.sendMessage(
                SocketConnectionMessage.UserChange(
                    user = UserResponse.FriendUserResponse(
                        id = user.id.toHexString(),
                        username = user.username,
                        updatedAt = user.updatedAt.toEpochMilliseconds(),
                        profilePicUpdatedAt = user.profilePicUpdatedAt.toEpochMilliseconds(),
                        requesterId = requesterId.toHexString(),
                        birthDate = user.birthDate,
                        phoneNumber = user.phoneNumber,
                        userDescription = user.userDescription,
                        userStatus = user.userStatus,
                        nickName = nickName,
                        shareLocation = shareLocation,
                        lastSeen = user.lastSeen.toEpochMilliseconds()
                    ),
                    deleted = deleted
                ),
                receiverId = friendId
            )
        }
    }

    /**
     * Notify the changing user's own devices that they updated whether they share
     * their location with a specific friend. The friend's view of the relationship
     * is unaffected by this change, so only the changing user is pushed to.
     * @param changingUserId the user who changed their own sharing setting
     * @param friendId the friend this setting applies to
     * @param shareLocation the new sharing value (changingUserId -> friendId)
     */
    @OptIn(ExperimentalTime::class)
    fun notifyLocationSharingChange(changingUserId: ObjectId, friendId: ObjectId, shareLocation: Boolean) {
        val friend = userLookupService.findById(friendId) ?: return
        val friendship = friendsLookupService.findFriendship(changingUserId, friendId) ?: return
        //changingUserId's own nickname for the friend
        val nickName = friendsSettingsService.getFriendshipSetting(friendship.id, changingUserId)?.nickName

        socketConnectionHandler.sendMessage(
            SocketConnectionMessage.UserChange(
                user = UserResponse.FriendUserResponse(
                    id = friend.id.toHexString(),
                    username = friend.username,
                    updatedAt = friend.updatedAt.toEpochMilliseconds(),
                    profilePicUpdatedAt = friend.profilePicUpdatedAt.toEpochMilliseconds(),
                    requesterId = friendship.requesterId.toHexString(),
                    birthDate = friend.birthDate,
                    phoneNumber = friend.phoneNumber,
                    userDescription = friend.userDescription,
                    userStatus = friend.userStatus,
                    nickName = nickName,
                    shareLocation = shareLocation,
                    lastSeen = friend.lastSeen.toEpochMilliseconds()
                ),
                deleted = false
            ),
            receiverId = changingUserId
        )
    }

    /**
     * Push a wake-permission change to the changing user's OWN devices for multi-device sync.
     * The friend is deliberately not notified - they have no business knowing whether someone
     * allows them to wake them until they actually try.
     * @param changingUserId the user who changed who may wake them
     * @param friendId the friend the permission applies to
     * @param allowWake the new value (friendId may wake changingUserId)
     */
    @OptIn(ExperimentalTime::class)
    fun notifyWakePermissionChange(changingUserId: ObjectId, friendId: ObjectId, allowWake: Boolean) {
        val friend = userLookupService.findById(friendId) ?: return
        val friendship = friendsLookupService.findFriendship(changingUserId, friendId) ?: return
        //changingUserId's own settings row towards the friend. Send every field it carries - the
        //client replaces the whole user row, so omitting one would silently reset it.
        val setting = friendsSettingsService.getFriendshipSetting(friendship.id, changingUserId)

        socketConnectionHandler.sendMessage(
            SocketConnectionMessage.UserChange(
                user = UserResponse.FriendUserResponse(
                    id = friend.id.toHexString(),
                    username = friend.username,
                    updatedAt = friend.updatedAt.toEpochMilliseconds(),
                    profilePicUpdatedAt = friend.profilePicUpdatedAt.toEpochMilliseconds(),
                    requesterId = friendship.requesterId.toHexString(),
                    birthDate = friend.birthDate,
                    phoneNumber = friend.phoneNumber,
                    userDescription = friend.userDescription,
                    userStatus = friend.userStatus,
                    nickName = setting?.nickName,
                    shareLocation = setting?.shareLocation ?: false,
                    shareSpeedHeading = setting?.shareSpeedHeading ?: false,
                    shareSnailTrail = setting?.shareSnailTrail ?: false,
                    allowWake = allowWake,
                    lastSeen = friend.lastSeen.toEpochMilliseconds()
                ),
                deleted = false
            ),
            receiverId = changingUserId
        )
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

    fun notifyGroupUpdate(groupResponse: GroupResponse, deleted: Boolean) {

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
    }

    fun notifyEventUpdate(eventResponse: EventResponse, newEntry: Boolean, deleted: Boolean) {

        val toNotify = when (eventResponse.visibility) {
            // Only explicitly invited users can see this event
            EventVisibility.INVITED_FRIENDS_ONLY -> eventResponse.invitedUsers.toSet()
            // Everyone who can access the event (invited users + the creator's friends) gets notified
            EventVisibility.FRIENDS_ONLY, EventVisibility.PUBLIC ->
                (eventResponse.invitedUsers + friendsLookupService.getFriends(ObjectId(eventResponse.creatorId)).map { it.toHexString() }).toSet()
        }

        toNotify.forEach { user ->
            socketConnectionHandler.sendMessage(
                SocketConnectionMessage.EventChange(
                    event = eventResponse,
                    newEntry = newEntry,
                    deleted = deleted
                ),
                receiverId = ObjectId(user),
            )
        }

        // Firebase + APNs push for brand-new events (offline users only, creator excluded)
        if (newEntry) {
            val notification = NotificationResponse.EventNotificationResponse(
                eventId = eventResponse.id,
                eventTitle = eventResponse.title,
                creatorId = eventResponse.creatorId,
                creatorName = eventResponse.creatorName,
            )

            toNotify.forEach { userId ->
                // Don't push the creator — they already know
                if (userId == eventResponse.creatorId) return@forEach
                val userOid = ObjectId(userId)
                // Only push to users NOT connected via WebSocket (offline fallback)
                if (!socketConnectionHandler.isConnected(userOid)) {
                    firebaseMessagingService.sendNotificationToUser(userOid, notification)
                    apnsService.sendNotificationToUser(userOid, notification)
                }
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
        firebaseMessagingService.sendNotificationToUser(recipientId, notification)
        apnsService.sendNotificationToUser(recipientId, notification)
    }

    /**
     * Notify the original message sender that someone added a reaction.
     * Only fires on add (not on remove). Skipped if the reactor is the message sender.
     * The recipient is always the message sender; other group members are not notified.
     * Live UI sync (WebSocket MessageChange) is already handled by notifyMessageUpdate;
     * this only takes care of the push fallback when the recipient is offline.
     */
    fun notifyReactionAdded(message: Message, reactorId: ObjectId, reactionContent: String) {
        if (message.senderId == reactorId) return

        val recipient = message.senderId

        if (socketConnectionHandler.isConnected(recipient)) return

        val groupName = if (message.groupMessage) {
            groupLookupService.getGroupById(message.receiverId)?.name ?: "Unknown Group"
        } else null

        val groupId = if (message.groupMessage) message.receiverId else null

        firebaseMessagingService.sendReactionNotificationToUser(
            reactorId = reactorId,
            receiverId = recipient,
            reactionContent = reactionContent,
            msgId = message.id.toHexString(),
            groupMessage = message.groupMessage,
            messageType = message.msgType,
            groupName = groupName,
            groupId = groupId,
        )
        apnsService.sendReactionNotificationToUser(
            reactorId = reactorId,
            receiverId = recipient,
            reactionContent = reactionContent,
            msgId = message.id.toHexString(),
            groupMessage = message.groupMessage,
            messageType = message.msgType,
            groupName = groupName,
            groupId = groupId,
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
        if (!socketConnectionHandler.sendMessage(
                SocketConnectionMessage.FriendRequest(
                    requestingUser = requestingUser.toHexString(),
                    requestingUserName = userLookupService.getUsername(requestingUser),
                    accepted = accepted
                ),
                receiverId = receivingUser,
            )
        ) {
            firebaseMessagingService.sendFriendRequestNotificationToUser(
                senderId = requestingUser,
                receivingUserId = receivingUser,
                accepted = accepted
            )
            apnsService.sendFriendRequestNotificationToUser(
                senderId = requestingUser,
                receivingUserId = receivingUser,
                accepted = accepted
            )
        }
    }

}