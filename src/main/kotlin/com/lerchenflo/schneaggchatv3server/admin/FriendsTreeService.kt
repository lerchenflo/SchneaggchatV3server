@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.admin

import com.lerchenflo.schneaggchatv3server.repository.FriendshipRepository
import com.lerchenflo.schneaggchatv3server.repository.UserRepository
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import kotlin.time.ExperimentalTime

data class FriendsTreeNode(
    val userId: String,
    val username: String,
    /** Account creation time - the tree is ordered by this. */
    val createdAt: Long,
    /** When the friendship to this node's parent was first requested. Null on a root. */
    val friendsSince: Long?,
    /** Total accepted friendships this user has, shown as a badge. */
    val friendCount: Int,
    val children: List<FriendsTreeNode>,
)

data class FriendsTreeResponse(
    val roots: List<FriendsTreeNode>,
    val totalUsers: Int,
)

/**
 * Builds a "who likely invited whom" tree.
 *
 * For every user, their parent is the other party of their **earliest accepted friendship whose
 * partner registered before them** - the best available proxy for an invite, since the server never
 * recorded invites explicitly. Users whose earliest accepted friendship is with someone younger, and
 * users with no accepted friendships at all, become roots.
 *
 * `Friendship.createdAt` is the moment the first request between the pair was sent and is never
 * mutated afterwards (accept only bumps `updatedAt`), so it is a stable ordering key.
 * `Friendship.requesterId` is deliberately NOT used - it means "who acted last" and gets overwritten
 * by later blocks/re-requests.
 *
 * Because a parent always has a strictly earlier `User.createdAt` than its child, the result is
 * always a forest - cycles are impossible by construction.
 */
@Service
class FriendsTreeService(
    private val userRepository: UserRepository,
    private val friendshipRepository: FriendshipRepository,
) {

    fun buildTree(): FriendsTreeResponse {
        val users = userRepository.findAllByOrderByCreatedAtAsc()
        val usersById = users.associateBy { it.id }

        val accepted = friendshipRepository.findByStatus(FriendshipStatus.ACCEPTED)

        // userId -> every accepted friendship they are part of
        val friendshipsByUser = mutableMapOf<ObjectId, MutableList<AcceptedLink>>()
        for (friendship in accepted) {
            val a = friendship.userId1
            val b = friendship.userId2
            if (a !in usersById || b !in usersById) continue // partner account was deleted
            friendshipsByUser.getOrPut(a) { mutableListOf() } += AcceptedLink(b, friendship.createdAt.toEpochMilliseconds())
            friendshipsByUser.getOrPut(b) { mutableListOf() } += AcceptedLink(a, friendship.createdAt.toEpochMilliseconds())
        }

        val parentOf = mutableMapOf<ObjectId, ObjectId>()
        val friendsSince = mutableMapOf<ObjectId, Long>()

        for (user in users) {
            val links = friendshipsByUser[user.id] ?: continue
            val inviter = links
                .filter { link -> usersById[link.otherUserId]?.createdAt?.let { it < user.createdAt } == true }
                .minByOrNull { it.friendshipCreatedAt }
                ?: continue

            parentOf[user.id] = inviter.otherUserId
            friendsSince[user.id] = inviter.friendshipCreatedAt
        }

        val childrenOf = parentOf.entries
            .groupBy({ it.value }, { it.key })

        fun buildNode(user: User): FriendsTreeNode = FriendsTreeNode(
            userId = user.id.toHexString(),
            username = user.username,
            createdAt = user.createdAt.toEpochMilliseconds(),
            friendsSince = friendsSince[user.id],
            friendCount = friendshipsByUser[user.id]?.size ?: 0,
            children = (childrenOf[user.id] ?: emptyList())
                .mapNotNull { usersById[it] }
                .sortedBy { it.createdAt }
                .map { buildNode(it) },
        )

        val roots = users
            .filter { it.id !in parentOf }
            .map { buildNode(it) }

        return FriendsTreeResponse(roots = roots, totalUsers = users.size)
    }

    private data class AcceptedLink(val otherUserId: ObjectId, val friendshipCreatedAt: Long)
}
