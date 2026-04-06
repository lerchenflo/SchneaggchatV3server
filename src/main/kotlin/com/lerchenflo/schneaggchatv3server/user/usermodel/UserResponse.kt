@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user.usermodel

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import kotlin.time.ExperimentalTime


@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
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
        val userStatus: String



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

        //Custom to own user response:
        val email: String,
        val emailVerifiedAt: Long?,
        val createdAt: Long,


        //TODO: User profile pic privacy settings??


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




