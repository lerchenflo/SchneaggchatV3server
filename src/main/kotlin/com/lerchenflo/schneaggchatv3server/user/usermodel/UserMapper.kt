package com.lerchenflo.schneaggchatv3server.user.usermodel

import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import org.bson.types.ObjectId

/**
 * Builds this user's [UserResponse.SelfUserResponse]. The single construction site for the
 * self-user DTO, used by both /users/sync ([com.lerchenflo.schneaggchatv3server.user.UserService.serializeSyncUser])
 * and the realtime push ([com.lerchenflo.schneaggchatv3server.notifications.NotificationService.notifyUserUpdate]) -
 * building it twice by hand previously let fields (e.g. allowWakeGlobal) silently drop out of the
 * socket push.
 */
fun User.toSelfUserResponse(locationShared: Boolean, updatedAt: Long? = null): UserResponse.SelfUserResponse {
    return UserResponse.SelfUserResponse(
        id = id.toHexString(),
        username = username,
        updatedAt = updatedAt ?: this.updatedAt.toEpochMilliseconds(),
        profilePicUpdatedAt = profilePicUpdatedAt.toEpochMilliseconds(),
        birthDate = birthDate,
        userDescription = userDescription,
        userStatus = userStatus,
        phoneNumber = phoneNumber,
        email = email,
        emailVerifiedAt = emailVerifiedAt?.toEpochMilliseconds(),
        createdAt = createdAt.toEpochMilliseconds(),
        locationShared = locationShared,
        allowWakeGlobal = allowWakeGlobal,
        settings = settings,
    )
}

/**
 * Builds this user's [UserResponse.FriendUserResponse] for one friend. The single construction site
 * for the friend DTO, for the same reason as [toSelfUserResponse]: the client replaces the whole
 * user row with it, so a field forgotten in one hand-built copy silently resets that value.
 * The sharing / wake flags are the RECIPIENT's own settings towards this user.
 */
fun User.toFriendUserResponse(
    requesterId: ObjectId?,
    nickName: String?,
    shareLocation: Boolean,
    shareSpeedHeading: Boolean,
    shareSnailTrail: Boolean,
    allowWake: Boolean,
    updatedAt: Long? = null,
): UserResponse.FriendUserResponse {
    return UserResponse.FriendUserResponse(
        id = id.toHexString(),
        username = username,
        updatedAt = updatedAt ?: this.updatedAt.toEpochMilliseconds(),
        profilePicUpdatedAt = profilePicUpdatedAt.toEpochMilliseconds(),
        requesterId = requesterId?.toHexString(),
        birthDate = birthDate,
        userDescription = userDescription,
        userStatus = userStatus,
        nickName = nickName,
        phoneNumber = phoneNumber,
        shareLocation = shareLocation,
        shareSpeedHeading = shareSpeedHeading,
        shareSnailTrail = shareSnailTrail,
        allowWake = allowWake,
        lastSeen = lastSeen.toEpochMilliseconds(),
    )
}

fun User.toSimpleUserResponse(
    friendShipStatus: FriendshipStatus?,
    requesterId: ObjectId?,
    updatedAt: Long? = null,
): UserResponse.SimpleUserResponse {
    return UserResponse.SimpleUserResponse(
        id = id.toHexString(),
        username = username,
        updatedAt = updatedAt ?: this.updatedAt.toEpochMilliseconds(),
        profilePicUpdatedAt = profilePicUpdatedAt.toEpochMilliseconds(),
        friendShipStatus = friendShipStatus,
        requesterId = requesterId?.toHexString(),
    )
}

/**
 * This user as seen by the viewer whose [interaction] it is - the shape `/users/sync` hands out:
 * the friend row for an accepted friendship, the simple row for anything else. `updatedAt` is the
 * newer of the user's and the friendship's last change, so a client that stores it does not get
 * the same row again on its next sync.
 */
fun User.toUserResponseFor(interaction: FriendsLookupService.UserInteraction): UserResponse {
    val lastChangedAt = maxOf(
        updatedAt.toEpochMilliseconds(),
        interaction.lastChanged?.toEpochMilliseconds() ?: 0,
    )

    return if (interaction.status == FriendshipStatus.ACCEPTED) {
        toFriendUserResponse(
            requesterId = interaction.requesterId,
            nickName = interaction.nickName,
            shareLocation = interaction.shareLocation,
            shareSpeedHeading = interaction.shareSpeedHeading,
            shareSnailTrail = interaction.shareSnailTrail,
            allowWake = interaction.allowWake,
            updatedAt = lastChangedAt,
        )
    } else {
        toSimpleUserResponse(
            friendShipStatus = interaction.status,
            requesterId = interaction.requesterId,
            updatedAt = lastChangedAt,
        )
    }
}
