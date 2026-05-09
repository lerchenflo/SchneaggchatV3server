package com.lerchenflo.schneaggchatv3server.notifications

import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.group.model.GroupResponse
import com.lerchenflo.schneaggchatv3server.message.messagemodel.Message
import com.lerchenflo.schneaggchatv3server.message.messagemodel.MessageType
import com.lerchenflo.schneaggchatv3server.message.messagemodel.toMessageResponse
import com.lerchenflo.schneaggchatv3server.notifications.firebase.FirebaseService
import com.lerchenflo.schneaggchatv3server.notifications.websocket.SocketConnectionHandler
import com.lerchenflo.schneaggchatv3server.notifications.websocket.model.SocketConnectionMessage
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import org.bson.types.ObjectId
import org.springframework.stereotype.Service


/**
 * Notificationservice to notify the user about updates. if the user is connected via socket connection, send
 * via socket, else via firebase. not all updates are sent via firebase (Message updates etc do not matter if the
 * client is offline, he will sync on app start)
 */
@Service
class NotificationService(
    private val socketConnectionHandler: SocketConnectionHandler,
    private val firebaseMessagingService: FirebaseService,

    private val userLookupService: UserLookupService,
    private val groupLookupService: GroupLookupService
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
                        //Socket connection failed, use firebase
                        firebaseMessagingService.sendNewMessageNotificationToUser(
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

                //Message sending failed, use firebase if new, else ignore
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
                }

            }
        }



    }


    fun notifyUserUpdate(user: User, deleted: Boolean) {
        //TODO
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
        }
    }

}