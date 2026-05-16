package com.lerchenflo.schneaggchatv3server.notifications

import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse
import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.message.messagemodel.toMessageResponse
import com.lerchenflo.schneaggchatv3server.notifications.apns.ApnsService
import com.lerchenflo.schneaggchatv3server.notifications.firebase.FirebaseService
import com.lerchenflo.schneaggchatv3server.notifications.firebase.model.NotificationResponse
import com.lerchenflo.schneaggchatv3server.notifications.websocket.SocketConnectionHandler
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnectionMessage
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserResponse
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import kotlin.time.ExperimentalTime


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
    private val friendsLookupService: FriendsLookupService
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
                            groupName = groupName
                        )
                        apnsService.sendNewMessageNotificationToUser(
                            senderId = message.senderId,
                            receiverId = member.userid,
                            messageType = message.msgType,
                            messageContent = message.content,
                            msgId = message.id.toHexString(),
                            groupMessage = true,
                            groupName = groupName
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
                        groupName = null
                    )
                    apnsService.sendNewMessageNotificationToUser(
                        senderId = message.senderId,
                        receiverId = message.receiverId,
                        messageType = message.msgType,
                        messageContent = message.content,
                        msgId = message.id.toHexString(),
                        groupMessage = false,
                        groupName = null
                    )
                }

            }
        }



    }


    @OptIn(ExperimentalTime::class)
    fun notifyUserUpdate(user: User, deleted: Boolean) {
        // Notify the user themselves
        socketConnectionHandler.sendMessage(
            SocketConnectionMessage.UserChange(
                user = UserResponse.SelfUserResponse(
                    id = user.id.toHexString(),
                    username = user.username,
                    updatedAt = user.updatedAt.toEpochMilliseconds(),
                    profilePicUpdatedAt = user.profilePicUpdatedAt.toEpochMilliseconds(),
                    birthDate = user.birthDate,
                    userDescription = user.userDescription,
                    userStatus = user.userStatus,
                    email = user.email,
                    emailVerifiedAt = user.emailVerifiedAt?.toEpochMilliseconds(),
                    createdAt = user.createdAt.toEpochMilliseconds(),
                ),
                deleted = deleted
            ),
            receiverId = user.id
        )

        // Notify friends
        friendsLookupService.getFriendsForUserUpdate(user.id).forEach { (friendId, requesterId, nickName) ->
            socketConnectionHandler.sendMessage(
                SocketConnectionMessage.UserChange(
                    user = UserResponse.FriendUserResponse(
                        id = user.id.toHexString(),
                        username = user.username,
                        updatedAt = user.updatedAt.toEpochMilliseconds(),
                        profilePicUpdatedAt = user.profilePicUpdatedAt.toEpochMilliseconds(),
                        requesterId = requesterId.toHexString(),
                        birthDate = user.birthDate,
                        userDescription = user.userDescription,
                        userStatus = user.userStatus,
                        nickName = nickName
                    ),
                    deleted = deleted
                ),
                receiverId = friendId
            )
        }
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

        firebaseMessagingService.sendReactionNotificationToUser(
            reactorId = reactorId,
            receiverId = recipient,
            reactionContent = reactionContent,
            msgId = message.id.toHexString(),
            groupMessage = message.groupMessage,
            messageType = message.msgType,
            groupName = groupName,
        )
        apnsService.sendReactionNotificationToUser(
            reactorId = reactorId,
            receiverId = recipient,
            reactionContent = reactionContent,
            msgId = message.id.toHexString(),
            groupMessage = message.groupMessage,
            messageType = message.msgType,
            groupName = groupName,
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