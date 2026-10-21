@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user.usermodel

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import org.bson.types.ObjectId
import kotlin.time.ExperimentalTime


@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "_class"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = UserResponse.SimpleUserResponse::class, name = "simple"),
    JsonSubTypes.Type(value = UserResponse.FriendUserResponse::class, name = "friend"),
    JsonSubTypes.Type(value = UserResponse.SelfUserResponse::class, name = "self")
)
/**
 * Response to use when syncing users
 */
sealed interface UserResponse {

    //Common data which every response contains
    val id: String
    val username: String
    val updatedAt: Long
    val profilePicUpdatedAt: Long

    //Response for a user (Not yourself and not your friend)
    data class SimpleUserResponse(
        override val id: String,
        override val username: String,
        override val updatedAt: Long,
        override val profilePicUpdatedAt: Long,

        //Custom to simpleuserresponse:
        val friendShipStatus: FriendshipStatus?,
        val requesterId: String?,

        ) : UserResponse

    //Response for a friend (He accepted your request)
    data class FriendUserResponse(
        override val id: String,
        override val username: String,
        override val updatedAt: Long,
        override val profilePicUpdatedAt: Long,

        val requesterId: String?, //Who requested the friendship

        //Custom to friend response:
        val birthDate: String,
        val userDescription: String,
        val userStatus: String,

        val nickName: String?,

        //Optional phone number, shared with accepted friends
        val phoneNumber: String? = null,

        //What the recipient of this response shares with this friend on the map
        val shareLocation: Boolean = false,
        val shareSpeedHeading: Boolean = false,
        //Whether the recipient shares their snail trail (full 24h history) with this friend
        val shareSnailTrail: Boolean = false,

        //Whether the recipient allows this friend to wake them (alarm ringtone). Opt-in.
        val allowWake: Boolean = false,

        //Last time this friend's final WebSocket session disconnected. Null if never seen offline yet.
        val lastSeen: Long? = null,

        ) : UserResponse

    //Response for yourself (You request your own data)
    data class SelfUserResponse(
        override val id: String,
        override val username: String,
        override val updatedAt: Long,
        override val profilePicUpdatedAt: Long,

        //Custom to friend response
        val birthDate: String,
        val userDescription: String,
        val userStatus: String,

        //Optional phone number, only visible to yourself and accepted friends
        val phoneNumber: String? = null,

        //Custom to own user response:
        val email: String,
        val emailVerifiedAt: Long?,
        val createdAt: Long,

        //Auto-derived: does this user share their location with at least one accepted friend
        val locationShared: Boolean = false,

        //Master switch: may anyone wake this user at all. Opt-in.
        val allowWakeGlobal: Boolean = false,

        //TODO: User profile pic privacy settings??

        //Per-user app settings (theme, language, pinned chats, ...), synced across devices
        val settings: PersonalUserSettings = PersonalUserSettings(),

    ) : UserResponse
}

/**
 * Response to use when showing the new friends screen
 */
data class NewFriendsUserResponse(
    val id: String,
    val username: String,
    val commonFriendCount: Int,
)

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




