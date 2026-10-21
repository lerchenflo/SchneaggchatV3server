package com.lerchenflo.schneaggchatv3server.user.friends

import com.lerchenflo.schneaggchatv3server.repository.FriendshipRepository
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.Friendship
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import kotlin.time.Instant

@Component
class FriendsLookupService(
    private val friendshipRepository: FriendshipRepository,
    private val friendshipSettingsService: FriendsSettingsService
) {

    /**
     * Get all friends for a user (accepted friendships only)
     */
    fun getFriends(userId: ObjectId): List<ObjectId> {
        return friendshipRepository.findByUserId1OrUserId2(userId, userId)
            .filter { it.status == FriendshipStatus.ACCEPTED }
            .map { if (it.userId1 == userId) it.userId2 else it.userId1 }
    }

    data class FriendWithNickname(
        val friendId: ObjectId,
        val requesterId: ObjectId,
        val nickName: String?,
        val shareLocation: Boolean,
        val shareSpeedHeading: Boolean = false,
        val shareSnailTrail: Boolean = false,
        //Keep new fields at the END - NotificationService destructures this positionally.
        val allowWake: Boolean = false,
    )

    fun getFriendsForUserUpdate(userId: ObjectId): List<FriendWithNickname> {
        return friendshipRepository.findByUserId1OrUserId2(userId, userId)
            .filter { it.status == FriendshipStatus.ACCEPTED }
            .map { friendship ->
                val friendId = if (friendship.userId1 == userId) friendship.userId2 else friendship.userId1
                //Recipient's (friendId's) own setting: nickname they gave to userId, and what
                //they (friendId) share with userId (location, speed/heading, snail trail)
                val friendSetting = friendshipSettingsService.getFriendshipSetting(friendship.id, friendId)
                FriendWithNickname(
                    friendId = friendId,
                    requesterId = friendship.requesterId,
                    nickName = friendSetting?.nickName,
                    shareLocation = friendSetting?.shareLocation ?: false,
                    shareSpeedHeading = friendSetting?.shareSpeedHeading ?: false,
                    shareSnailTrail = friendSetting?.shareSnailTrail ?: false,
                    allowWake = friendSetting?.allowWake ?: false,
                )
            }
    }

    data class UserInteraction(
        val userId: ObjectId,
        val status: FriendshipStatus,
        val requesterId: ObjectId,
        val lastChanged: Instant? = null,
        val nickName: String? = null,
        val shareLocation: Boolean = false,
        val shareSpeedHeading: Boolean = false,
        val shareSnailTrail: Boolean = false,
        val allowWake: Boolean = false,
    )

    fun getAllInteractions(userId: ObjectId): List<UserInteraction> {
        return friendshipRepository.findByUserId1OrUserId2(userId, userId)
            .map { friendship -> friendship.toInteraction(viewerId = userId) }
    }

    /**
     * [viewerId]'s interaction with [otherUserId], or null if there is no friendship row between
     * them (never interacted, request declined or friend removed).
     */
    fun getInteraction(viewerId: ObjectId, otherUserId: ObjectId): UserInteraction? {
        return findFriendship(viewerId, otherUserId)?.toInteraction(viewerId = viewerId)
    }

    /**
     * Every user with a friendship row towards [userId] (any status), each paired with their OWN
     * interaction with [userId] (their nickname for [userId], what they share with [userId]).
     * These are the users a change to [userId] has to be pushed to.
     */
    fun getCounterpartInteractions(userId: ObjectId): List<Pair<ObjectId, UserInteraction>> {
        return friendshipRepository.findByUserId1OrUserId2(userId, userId)
            .map { friendship ->
                val counterpartId = if (friendship.userId1 == userId) friendship.userId2 else friendship.userId1
                counterpartId to friendship.toInteraction(viewerId = counterpartId)
            }
    }

    /** This friendship as seen by [viewerId]: the other user, plus [viewerId]'s own settings towards them. */
    private fun Friendship.toInteraction(viewerId: ObjectId): UserInteraction {
        val otherUserId = if (userId1 == viewerId) userId2 else userId1

        //The viewer's own setting: nickname they gave to otherUserId, and
        //whether they (the viewer) share their location with otherUserId
        val friendshipSetting = friendshipSettingsService.getFriendshipSetting(id, viewerId)

        return UserInteraction(
            userId = otherUserId,
            status = status,
            requesterId = requesterId,
            //Settings changes must move this timestamp too, otherwise a pure setting flip
            //(nickname, sharing toggles) is only ever delivered by the socket and a client
            //that was offline at the time keeps a stale value forever.
            lastChanged = maxOf(
                updatedAt,
                friendshipSetting?.updatedAt ?: Instant.DISTANT_PAST
            ),
            nickName = friendshipSetting?.nickName,
            shareLocation = friendshipSetting?.shareLocation ?: false,
            shareSpeedHeading = friendshipSetting?.shareSpeedHeading ?: false,
            shareSnailTrail = friendshipSetting?.shareSnailTrail ?: false,
            allowWake = friendshipSetting?.allowWake ?: false,
        )
    }

    /**
     * Whether [userId] currently shares their location with at least one ACCEPTED friend (i.e. their
     * own FriendshipSetting.shareLocation is true on at least one ACCEPTED friendship). Computed live -
     * this replaces the old denormalized User.locationShared field.
     */
    fun hasActiveLocationSharing(userId: ObjectId): Boolean {
        return getAllInteractions(userId).any { it.status == FriendshipStatus.ACCEPTED && it.shareLocation }
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
     * True when a friendship row exists between the two users and it is not blocked - i.e. any
     * ongoing relationship (ACCEPTED or a still-open PENDING request), but not a DECLINED or
     * BLOCKED one. Used to decide softer visibility (e.g. seeing a group's picture through a
     * friend in it).
     */
    fun hasNonBlockedFriendship(userId1: ObjectId, userId2: ObjectId): Boolean {
        val status = findFriendship(userId1, userId2)?.status ?: return false
        return status == FriendshipStatus.ACCEPTED || status == FriendshipStatus.PENDING
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
    fun findFriendship(userId1: ObjectId, userId2: ObjectId): Friendship? {
        val min = minOf(userId1, userId2)
        val max = maxOf(userId1, userId2)
        return friendshipRepository.findByUserId1AndUserId2(min, max)
    }

    fun saveFriendship(friendship: Friendship) : Friendship {
        return friendshipRepository.save(friendship)
    }

    fun deleteFriendshipEntry(friendship: Friendship) {

        friendshipRepository.delete(friendship)
    }

}