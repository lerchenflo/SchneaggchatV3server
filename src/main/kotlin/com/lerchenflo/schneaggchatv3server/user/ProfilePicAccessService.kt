package com.lerchenflo.schneaggchatv3server.user

import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.user.usermodel.allows
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * The single place that decides whether a viewer may see a user's profile picture, by the owner's
 * [com.lerchenflo.schneaggchatv3server.user.usermodel.ProfilePicVisibility] (rule in
 * [com.lerchenflo.schneaggchatv3server.user.usermodel.allows]). Used by `/users/profilepic/{id}`,
 * the user rows of `/users/sync` and the socket `userchange`, and the search results.
 */
@Service
class ProfilePicAccessService(
    private val friendsLookupService: FriendsLookupService,
    private val groupLookupService: GroupLookupService,
) {

    /** Single check: reads the friendship row and, only when needed, the shared groups. */
    fun canSee(viewerId: ObjectId, owner: User): Boolean {
        if (viewerId == owner.id) return true

        val interaction = friendsLookupService.getInteraction(viewerId = viewerId, otherUserId = owner.id)
        return canSee(viewerId, owner, interaction) {
            owner.id in groupLookupService.getCoMemberIds(viewerId)
        }
    }

    /**
     * Batch check without own reads: [interaction] is the friendship row between the two (null when
     * there is none), [sharesGroup] answers whether they share a group - pass a lookup in a co-member
     * set loaded once for the whole batch (sharing a group is symmetric, so either side's set works).
     */
    fun canSee(
        viewerId: ObjectId,
        owner: User,
        interaction: FriendsLookupService.UserInteraction?,
        sharesGroup: () -> Boolean,
    ): Boolean {
        if (viewerId == owner.id) return true

        return owner.settings.profilePicVisibility.allows(
            ownerId = owner.id,
            friendshipStatus = interaction?.status,
            requesterId = interaction?.requesterId,
            sharesGroup = sharesGroup,
        )
    }

    /** Everyone who shares a group with [userId], for [canSee] batches. */
    fun coMemberIds(userId: ObjectId): Set<ObjectId> = groupLookupService.getCoMemberIds(userId)
}
