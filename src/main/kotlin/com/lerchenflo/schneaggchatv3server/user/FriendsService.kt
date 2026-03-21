@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user

import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.FriendshipRepository
import com.lerchenflo.schneaggchatv3server.repository.UserRepository
import com.lerchenflo.schneaggchatv3server.user.friendshipmodel.Friendship
import com.lerchenflo.schneaggchatv3server.user.friendshipmodel.FriendshipStatus
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.LogType
import com.lerchenflo.schneaggchatv3server.util.LoggingService
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Component
class FriendsService(
    private val friendshipRepository: FriendshipRepository,
    private val loggingService: LoggingService,
    private val userRepository: UserRepository,
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
        val existing = findFriendship(fromUserId, toUserId)

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
                    friendshipRepository.save(existing)
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

        return friendshipRepository.save(friendship)
    }

    /**
     * Accept a pending friend request
     * @throws IllegalArgumentException if request doesn't exist or not pending
     */
    fun acceptFriendRequest(acceptingUserId: ObjectId, requesterId: ObjectId): Friendship {
        val friendship = findFriendship(acceptingUserId, requesterId)
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

        return friendshipRepository.save(friendship)
    }

    /**
     * Decline a pending friend request
     * Sets expiration to 30 days from now
     * @param decliningUserId the user which declines the request
     * @param requesterId the chat for which the friend request is declined (the one who sent the request)
     */
    fun declineFriendRequest(decliningUserId: ObjectId, requesterId: ObjectId) {
        val friendship = findFriendship(decliningUserId, requesterId)
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
        val friendship = findFriendship(userId, friendId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Friendship not found")

        friendshipRepository.delete(friendship)

        //TODO: Delete settings for this friendship
    }

    /**
     * Block a user - prevents any future friend requests
     */
    fun blockUser(blockingUserId: ObjectId, blockedUserId: ObjectId): Friendship {
        val existing = findFriendship(blockingUserId, blockedUserId)

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

        return friendshipRepository.save(friendship)
    }

    /**
     * Unblock a user
     */
    fun unblockUser(unblockingUserId: ObjectId, blockedUserId: ObjectId): Boolean {
        val friendship = findFriendship(unblockingUserId, blockedUserId)
            ?: return false

        require(friendship.status == FriendshipStatus.BLOCKED) {
            "User is not blocked"
        }

        friendshipRepository.delete(friendship)
        return true
    }

    /**
     * Get all friends for a user (accepted friendships only)
     */
    fun getFriends(userId: ObjectId): List<ObjectId> {
        return friendshipRepository.findByUserId1OrUserId2(userId, userId)
            .filter { it.status == FriendshipStatus.ACCEPTED }
            .map { if (it.userId1 == userId) it.userId2 else it.userId1 }
    }

    data class UserInteraction(
        val userId: ObjectId,
        val status: FriendshipStatus,
        val requesterId: ObjectId,
        val lastChanged: Instant? = null,
    )

    fun getAllInteractions(userId: ObjectId): List<UserInteraction> {
        return friendshipRepository.findByUserId1OrUserId2(userId, userId)
            .map { friendship ->
                val otherUserId = if (friendship.userId1 == userId) {
                    friendship.userId2
                } else {
                    friendship.userId1
                }
                UserInteraction(
                    userId = otherUserId,
                    status = friendship.status,
                    requesterId = friendship.requesterId,
                    lastChanged = friendship.updatedAt,
                )
            }
    }

    /**
     * Get all users that the given user has not interacted with yet
     * (no friendship, pending request, block, or declined status)
     * @param userId The user to check interactions for
     * @return List of ObjectIds for users with no interaction history
     */
    fun getUsersWithNoInteraction(userId: ObjectId, allUserIds: List<ObjectId>): List<ObjectId> {
        // Get all users this user has interacted with
        val interactedUserIds = friendshipRepository.findByUserId1OrUserId2(userId, userId)
            .map { friendship ->
                if (friendship.userId1 == userId) friendship.userId2 else friendship.userId1
            }
            .toSet()

        // Return all users excluding the current user and those with interactions
        return allUserIds.filter { it != userId && it !in interactedUserIds }
    }


    /**
     * Get all pending friend requests received by a user
     */
    fun getPendingRequests(userId: ObjectId): List<Friendship> {
        return friendshipRepository.findByUserId1OrUserId2(userId, userId)
            .filter { it.status == FriendshipStatus.PENDING && it.requesterId != userId }
    }

    /**
     * Get all pending friend requests sent by a user
     */
    fun getSentRequests(userId: ObjectId): List<Friendship> {
        return friendshipRepository.findByUserId1OrUserId2(userId, userId)
            .filter { it.status == FriendshipStatus.PENDING && it.requesterId == userId }
    }

    /**
     * Check if two users are friends
     */
    fun areFriends(userId1: ObjectId, userId2: ObjectId): Boolean {
        val friendship = findFriendship(userId1, userId2)
        return friendship?.status == FriendshipStatus.ACCEPTED
    }

    /**
     * Get all users that share at least one mutual friend with the given user,
     * excluding users already interacted with
     * @return Map of userId to count of common friends (only users with count > 0)
     */
    fun getUsersWithCommonFriends(userId: ObjectId): Map<ObjectId, Int> {
        val myFriends = getFriends(userId).toSet()

        if (myFriends.isEmpty()) {
            return emptyMap()
        }

        // Get all users that have interacted with this user (to exclude them)
        val interactedUserIds = friendshipRepository.findByUserId1OrUserId2(userId, userId)
            .map { friendship ->
                if (friendship.userId1 == userId) friendship.userId2 else friendship.userId1
            }
            .toSet()

        // Map to store potential friends and their common friend count
        val potentialFriends = mutableMapOf<ObjectId, Int>()

        // For each of my friends, get their friends
        myFriends.forEach { friendId ->
            val friendsOfFriend = getFriends(friendId)

            friendsOfFriend.forEach { potentialFriendId ->
                // Skip if it's me or if I've already interacted with them
                if (potentialFriendId != userId && potentialFriendId !in interactedUserIds) {
                    potentialFriends[potentialFriendId] =
                        potentialFriends.getOrDefault(potentialFriendId, 0) + 1
                }
            }
        }

        return potentialFriends
    }

    fun getCommonFriendCount(userId1: ObjectId, userId2: ObjectId): Int {
        val user1Friends = getFriends(userId1).toSet()
        val user2Friends = getFriends(userId2).toSet()
        return user1Friends.intersect(user2Friends).size
    }

    /**
     * Helper function to find a friendship between two users (regardless of order)
     */
    private fun findFriendship(userId1: ObjectId, userId2: ObjectId): Friendship? {
        val min = minOf(userId1, userId2)
        val max = maxOf(userId1, userId2)
        return friendshipRepository.findByUserId1AndUserId2(min, max)
    }

}