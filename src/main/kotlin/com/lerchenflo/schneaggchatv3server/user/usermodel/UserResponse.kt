@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user.usermodel

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
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




