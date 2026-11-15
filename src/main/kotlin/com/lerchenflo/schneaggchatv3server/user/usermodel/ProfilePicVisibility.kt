package com.lerchenflo.schneaggchatv3server.user.usermodel

import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import org.bson.types.ObjectId

/**
 * Who may see a user's profile picture, chosen by the owner in [PersonalUserSettings.profilePicVisibility].
 * Sent and stored by its name.
 */
enum class ProfilePicVisibility {
    /** Every logged-in user, strangers included (search results, unknown-user popup). */
    PUBLIC,

    /** Accepted friends and everyone who shares a group with the owner (event groups count). The default. */
    FRIENDS_AND_GROUPS,

    /** Accepted friends only. */
    FRIENDS,
}

/**
 * Whether a viewer may see the picture of the owner whose setting this is. The viewer is never the
 * owner here - the owner always sees their own picture, callers check that first.
 *
 * [friendshipStatus] and [requesterId] describe the friendship row between the two (null when there
 * is none). [sharesGroup] is only called when the answer depends on it.
 *
 * - A pending request is one-sided: its receiver sees the sender's picture whatever the sender chose,
 *   the sender sees the receiver's only when the receiver's setting allows it.
 * - A declined or blocked row unlocks nothing; a public picture or a shared group still shows it.
 */
fun ProfilePicVisibility.allows(
    ownerId: ObjectId,
    friendshipStatus: FriendshipStatus?,
    requesterId: ObjectId?,
    sharesGroup: () -> Boolean,
): Boolean {
    if (friendshipStatus == FriendshipStatus.PENDING && requesterId == ownerId) return true

    return when (this) {
        ProfilePicVisibility.PUBLIC -> true
        ProfilePicVisibility.FRIENDS -> friendshipStatus == FriendshipStatus.ACCEPTED
        ProfilePicVisibility.FRIENDS_AND_GROUPS -> friendshipStatus == FriendshipStatus.ACCEPTED || sharesGroup()
    }
}
