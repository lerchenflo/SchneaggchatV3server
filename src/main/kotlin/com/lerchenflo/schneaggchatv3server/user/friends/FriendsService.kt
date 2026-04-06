package com.lerchenflo.schneaggchatv3server.user.friends

import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.Friendship
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock

@Component
class FriendsService(
    private val friendsLookupService: FriendsLookupService,
    private val loggingService: LoggingService,
    private val notificationService: NotificationService
    ) {

    /**
     * Send a friend request from one user to another
     * @return The created Friendship or existing one if already exists
     * @throws IllegalArgumentException if users try to friend themselves
     */
    fun sendFriendRequest(fromUserId: ObjectId, toUserId: ObjectId): Friendship {
        require(fromUserId != toUserId) { "Users cannot send friend requests to themselves" }

        // Check if friendship already exists (in any direction)
        val existing = friendsLookupService.findFriendship(fromUserId, toUserId)

        loggingService.log(
            userId = fromUserId,
            logType = LogType.FRIEND_REQUEST_SENT,
        )

        if (existing != null) {
            return when (existing.status) {
                FriendshipStatus.ACCEPTED -> existing // Already friends
                FriendshipStatus.PENDING -> {
                    // If the other user already sent a request, auto-accept
                    if (existing.requesterId == toUserId) {
                        acceptFriendRequest(fromUserId, toUserId)
                    } else {
                        existing // Request already sent
                    }
                }
                FriendshipStatus.DECLINED -> {
                    // Resend request - update existing record
                    existing.apply {
                        status = FriendshipStatus.PENDING
                        updatedAt = Clock.System.now()
                    }
                    friendsLookupService.saveFriendship(existing)
                }
                FriendshipStatus.BLOCKED ->
                    throw IllegalStateException("Cannot send friend request - user is blocked")
            }
        }

        // Create new friendship request
        val friendship = Friendship(
            userId1 = minOf(fromUserId, toUserId),
            userId2 = maxOf(fromUserId, toUserId),
            requesterId = fromUserId,
            status = FriendshipStatus.PENDING
        )


        notificationService.notifyFriendRequest(
            requestingUser = fromUserId,
            receivingUser = toUserId,
            accepted = false
        )

        return friendsLookupService.saveFriendship(friendship)
    }

    /**
     * Accept a pending friend request
     * @throws IllegalArgumentException if request doesn't exist or not pending
     */
    fun acceptFriendRequest(acceptingUserId: ObjectId, requesterId: ObjectId): Friendship {
        val friendship = friendsLookupService.findFriendship(acceptingUserId, requesterId)
            ?: throw IllegalArgumentException("Friend request not found")

        require(friendship.status == FriendshipStatus.PENDING) {
            "Cannot accept - friendship status is ${friendship.status}"
        }

        require(friendship.requesterId == requesterId) {
            "Only the recipient can accept a friend request"
        }

        friendship.status = FriendshipStatus.ACCEPTED
        friendship.updatedAt = Clock.System.now()

        AppLogger.info("New friendship saved")

        notificationService.notifyFriendRequest(
            requestingUser = acceptingUserId,
            receivingUser = requesterId,
            accepted = true,
        )

        return friendsLookupService.saveFriendship(friendship)
    }

    /**
     * Decline a pending friend request
     * Sets expiration to 30 days from now
     * @param decliningUserId the user which declines the request
     * @param requesterId the chat for which the friend request is declined (the one who sent the request)
     */
    fun declineFriendRequest(decliningUserId: ObjectId, requesterId: ObjectId) {
        val friendship = friendsLookupService.findFriendship(decliningUserId, requesterId)
            ?: throw IllegalArgumentException("Friend request not found")

        require(friendship.status == FriendshipStatus.PENDING) {
            "Cannot decline - friendship status is ${friendship.status}"
        }

        //You can only deny friend requests from the other user, or cancel your own
        require(friendship.requesterId == requesterId /* Decline request from others*/
                || friendship.requesterId == decliningUserId /* Decline your own request (you requested and you decline)*/) {
            "Only the recipient can decline a friend request"
        }

        //Delete friendship (there is none)
        removeFriend(decliningUserId, requesterId)
    }

    /**
     * Remove/unfriend a user
     */
    fun removeFriend(userId: ObjectId, friendId: ObjectId) {
        val friendship = friendsLookupService.findFriendship(userId, friendId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Friendship not found")

        friendsLookupService.deleteFriendship(friendship)
    }

    /**
     * Block a user - prevents any future friend requests
     */
    fun blockUser(blockingUserId: ObjectId, blockedUserId: ObjectId): Friendship {
        val existing = friendsLookupService.findFriendship(blockingUserId, blockedUserId)

        val friendship = existing?.copy(
            status = FriendshipStatus.BLOCKED,
            requesterId = blockingUserId,
            updatedAt = Clock.System.now()
        ) ?: Friendship(
            userId1 = minOf(blockingUserId, blockedUserId),
            userId2 = maxOf(blockingUserId, blockedUserId),
            requesterId = blockingUserId,
            status = FriendshipStatus.BLOCKED
        )

        return friendsLookupService.saveFriendship(friendship)
    }

    /**
     * Unblock a user
     */
    fun unblockUser(unblockingUserId: ObjectId, blockedUserId: ObjectId): Boolean {
        val friendship = friendsLookupService.findFriendship(unblockingUserId, blockedUserId)
            ?: return false

        require(friendship.status == FriendshipStatus.BLOCKED) {
            "User is not blocked"
        }

        //TODO: User is unblocked, they do not know each other now?
        friendsLookupService.deleteFriendship(friendship)
        return true
    }



}