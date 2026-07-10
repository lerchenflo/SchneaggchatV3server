package com.lerchenflo.schneaggchatv3server.user

import com.lerchenflo.schneaggchatv3server.core.security.HashEncoder
import com.lerchenflo.schneaggchatv3server.group.GroupLookupService
import com.lerchenflo.schneaggchatv3server.message.MessageLookupService
import com.lerchenflo.schneaggchatv3server.notifications.NotificationService
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.repository.UserRepository
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsLookupService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsSettingsService
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipSetting
import com.lerchenflo.schneaggchatv3server.user.friends.friendshipmodel.FriendshipStatus
import com.lerchenflo.schneaggchatv3server.user.usermodel.NewFriendsUserResponse
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserRequest
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserResponse
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.ImageManager
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.Locale.getDefault
import kotlin.time.Clock

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userLookupService: UserLookupService,

    private val friendshipsService: FriendsService,
    private val friendsLookupService: FriendsLookupService,
    private val friendsSettingsService: FriendsSettingsService,

    private val groupLookupService: GroupLookupService,

    private val messageLookupService: MessageLookupService,

    private val hashEncoder: HashEncoder,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val imageManager: ImageManager,
    private val notificationService: NotificationService,

    @Value("\${defaultaccount.password}") private val defaultPassword: String,

    ) {

    fun ensureTestaccount(): User {
        val username = "testaccount"
        return userLookupService.findByUsername(username) ?: run {

            val now = Clock.System.now()
            userLookupService.save(
                User(
                    username = username,
                    hashedPassword = hashEncoder.encode(defaultPassword),
                    email = "defaultuser@schneaggchat.com",
                    userDescription = "",
                    userStatus = "Default Test Account for Google Play / App store",
                    birthDate = "2000-01-01",
                    createdAt = now,
                    updatedAt = now,
                    emailVerifiedAt = now
                )
            )
        }

    }



    data class IdTimeStamp(
        @field:NotBlank(message = "ID must not be blank")
        @field:Size(max = 100, message = "ID too long")
        val id: String,
        @field:NotBlank(message = "Timestamp must not be blank")
        @field:Size(max = 30, message = "Timestamp too long")
        val timeStamp: String
    )

    data class UserSyncResponse(val updatedUsers: List<UserResponse>, val deletedUsers: List<String>)
    fun userIdSync(idTimeStamps: List<IdTimeStamp>, requesterId: ObjectId) : UserSyncResponse{
        //Users which the client has on his device
        val clientUsers = idTimeStamps.associate {
            it.id to it.timeStamp
        }

        //All users this current client has interacted with (Friends, requested, blocked etc)
        var allFriendInteractions = friendsLookupService.getAllInteractions(requesterId)

        //Add own user (also needs to be synced)
        allFriendInteractions = allFriendInteractions + FriendsLookupService.UserInteraction(
            userId = requesterId,
            status = FriendshipStatus.ACCEPTED,
            requesterId = requesterId,
        )

        // Create a map for easy lookup
        val interactionMap = allFriendInteractions.associateBy { it.userId }

        //Find all friend objects from interactions
        val currentFriends = userRepository.findAllById(allFriendInteractions.map { interaction ->
            interaction.userId
        })

        val usersToAdd = currentFriends
            .filter { it.id.toHexString() !in clientUsers.keys }

        val usersToUpdate = currentFriends
            .filter { user ->
                clientUsers[user.id.toHexString()]?.toLong()?.let { clientTimestamp ->
                    user.updatedAt.toEpochMilliseconds() > clientTimestamp ||
                            (interactionMap[user.id]?.lastChanged?.toEpochMilliseconds() ?: 0) > clientTimestamp
                } ?: false
            }

        val currentFriendIdStrings = allFriendInteractions.map { it.userId.toHexString() }.toSet()
        val usersToRemove = clientUsers.keys.filter { it !in currentFriendIdStrings && it != requesterId.toHexString() } //do not remove own user

        val finalExistingToUpdate = usersToUpdate + usersToAdd

        val addusers = finalExistingToUpdate.map { user ->

            //Calculate newest lastchanged timestamp
            val userTimestamp = user.updatedAt.toEpochMilliseconds()
            val friendshipTimestamp = interactionMap[user.id]?.lastChanged?.toEpochMilliseconds() ?: 0
            val newestTimestamp = maxOf(userTimestamp, friendshipTimestamp)

            serializeSyncUser(
                user = user,
                requestingUserId = requesterId,
                friendshipStatus = interactionMap[user.id]?.status,
                requesterId = interactionMap[user.id]?.requesterId,
                lastChangedAt = newestTimestamp,
                nickName = interactionMap[user.id]?.nickName,
                shareLocation = interactionMap[user.id]?.shareLocation ?: false,
                shareSpeedHeading = interactionMap[user.id]?.shareSpeedHeading ?: false,
                shareSnailTrail = interactionMap[user.id]?.shareSnailTrail ?: false,
            )
        }

        return UserSyncResponse(
            updatedUsers = addusers,
            deletedUsers = usersToRemove
        )
    }

    fun getAvailableUsers(
        searchTerm: String?,
        requestingUserId: ObjectId
    ) : List<NewFriendsUserResponse>{
        //Is user searching?
        if (searchTerm.isNullOrBlank()) {
            //User is not searching
            val allUserIds = userRepository.findAll().map { it.id }

            // Check if requesting user has any friendships
            val hasFriendships = friendsLookupService.getAllInteractions(requestingUserId).isNotEmpty()

            // Get users with no interaction
            val usersWithNoInteraction = friendsLookupService.getUsersWithNoInteraction(
                userId = requestingUserId,
                allUserIds = allUserIds
            )

            val eligibleUsers = if (hasFriendships) {
                // Return only users with common friends (at least 1)
                userRepository.findAllById(usersWithNoInteraction)
                    .filter { user ->
                        friendsLookupService.getCommonFriendCount(requestingUserId, user.id) > 0
                    }
            } else {
                // Return all users with no interaction
                userRepository.findAllById(usersWithNoInteraction)
            }

            return eligibleUsers.map { user ->
                NewFriendsUserResponse(
                    id = user.id.toHexString(),
                    username = user.username,
                    commonFriendCount = friendsLookupService.getCommonFriendCount(requestingUserId, user.id),
                )
            }
        } else {
            //return users searched by searchterm
            val searchResults = userRepository.findByUsernameContainingIgnoreCase(
                searchTerm.trim().lowercase(getDefault())
            )

            val interactedUserIds = friendsLookupService.getAllInteractions(requestingUserId)
                .map { it.userId }
                .toSet()

            return searchResults
                .filter { user ->
                    user.id != requestingUserId && // Exclude self
                            user.id !in interactedUserIds // Exclude already interacted users
                }
                .map { user ->
                    NewFriendsUserResponse(
                        id = user.id.toHexString(),
                        username = user.username,
                        commonFriendCount = friendsLookupService.getCommonFriendCount(
                            requestingUserId,
                            user.id
                        )
                    )
                }
                .sortedByDescending { it.commonFriendCount }
        }
    }


    fun changeUsername(requestingUserId: ObjectId, newName: String) {
        val normalizedNewName = newName.trim().lowercase(getDefault())
        val existingUser = userRepository.findByUsername(normalizedNewName)

        if (existingUser != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "A user with username $normalizedNewName already exists")
        }

        require(ValidationUtils.validateUsername(normalizedNewName)) { "New username is invalid: $normalizedNewName" }

        val user = userRepository.findById(requestingUserId).get()

        val updatedUser = user.copy(
            username = normalizedNewName,
            updatedAt = Clock.System.now()
        )
        userRepository.save(updatedUser)
        notificationService.notifyUserUpdate(updatedUser, deleted = false)
    }

    fun changeProfilepic(requestingUserId: ObjectId, newPic: MultipartFile){
        val user = userRepository.findById(requestingUserId).get()

        require(ValidationUtils.validatePicture(newPic)) { "New picture is invalid" }

        imageManager.saveProfilePic(
            image = newPic,
            userId = requestingUserId.toHexString(),
            group = false
        )

        val currenttime = Clock.System.now()

        val updatedUser = user.copy(
            updatedAt = currenttime,
            profilePicUpdatedAt = currenttime
        )
        userRepository.save(updatedUser)
        notificationService.notifyUserUpdate(updatedUser, deleted = false)
    }

    fun changeUserProfile(
        changingUserId: ObjectId,
        userRequest: UserRequest
    ) {
        val requestingUser = userLookupService.findById(changingUserId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

        val user = userLookupService.findById(userRequest.userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

        val timeStamp = Clock.System.now()


        //Change something about yourself (Status, email, birthdate)
        if (changingUserId.toHexString() == userRequest.userId) {

            val emailvalid = /* user.emailVerifiedAt != null && */ userRequest.newEmail != null

            //TODO: Send email to the old verified email address
            val somethingChanged = userRequest.newStatus != null || emailvalid || userRequest.newBirthDate != null

            if (userRequest.newStatus != null) {
                require(ValidationUtils.validateDescription(userRequest.newStatus)) { "New description is invalid" }
            }

            if (userRequest.newEmail != null) {
                require(ValidationUtils.validateEmail(userRequest.newEmail)) { "New email is invalid" }
                AppLogger.info("Setting new Email for ${user.username}: ${userRequest.newEmail}")
            }

            if (userRequest.newBirthDate != null) {
                require(ValidationUtils.validateBirthdate(userRequest.newBirthDate)) { "New birthdate is invalid" }
            }

            val updatedSelf = requestingUser.copy(
                updatedAt = if (somethingChanged) timeStamp else requestingUser.updatedAt,
                userStatus = userRequest.newStatus ?: requestingUser.userStatus,
                birthDate = userRequest.newBirthDate ?: requestingUser.birthDate,
                email = userRequest.newEmail?.lowercase(getDefault())?.trim() ?: requestingUser.email,
            )
            userLookupService.save(updatedSelf)

            if (somethingChanged) {
                notificationService.notifyUserUpdate(updatedSelf, deleted = false)
            }
        } else {
            //Change something about another user

            require(friendsLookupService.areFriends(requestingUser.id, user.id))

            //Retrieve friendship from db
            val friendshipEntry = friendsLookupService.findFriendship(requestingUser.id, user.id)!!
            val friendshipSetting = friendsSettingsService.getFriendshipSetting(friendshipEntry.id, requestingUser.id)

            //Check if something changed
            val somethingChanged = userRequest.newDescription != null || userRequest.newNickName != null

            if (userRequest.newDescription != null) {
                require(ValidationUtils.validateDescription(userRequest.newDescription)) { "New description is invalid" }
            }

            if (userRequest.newNickName != null) {
                require(ValidationUtils.validateNickname(userRequest.newNickName)) { "New nickname is invalid" }

                //update the nickname (Null or text)
                if (friendshipSetting != null) {
                    friendsSettingsService.saveFriendshipSetting(
                        friendshipSetting.copy(
                            updatedAt = timeStamp,
                            nickName = userRequest.newNickName.ifEmpty { null },
                        )
                    )
                } else {
                    friendsSettingsService.saveFriendshipSetting(
                        FriendshipSetting(
                            friendshipId = friendshipEntry.id,
                            userId = requestingUser.id,
                            nickName = userRequest.newNickName.ifEmpty { null }
                        )
                    )
                }
            }

            val updatedUser = user.copy(
                updatedAt = if (somethingChanged) timeStamp else user.updatedAt,
                userDescription = userRequest.newDescription ?: user.userDescription
            )
            userLookupService.save(updatedUser)
            if (somethingChanged) {
                notificationService.notifyUserUpdate(updatedUser, deleted = false)
            }
        }
    }


    data class PasswordChangeRequest(
        @field:NotBlank(message = "Old password must not be blank")
        @field:Size(max = 128, message = "Old password too long")
        val oldPassword: String,
        @field:NotBlank(message = "New password must not be blank")
        @field:Size(min = 8, max = 128, message = "New password must be between 8 and 128 characters")
        @field:Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}\$",
            message = "Password must be at least 8 characters long and contain at least one digit, uppercase and lowercase character."
        )
        val newPassword: String
    )

    fun changePassword(requestingUserId: ObjectId, passwordChangeRequest: PasswordChangeRequest) {
        val user = userRepository.findById(requestingUserId).get()

        require(
            hashEncoder.matches(passwordChangeRequest.oldPassword, user.hashedPassword)
        ) { "Old password does not match"}

        require(passwordChangeRequest.oldPassword != passwordChangeRequest.newPassword) { "Password can not be the same"}

        userRepository.save(user.copy(
            hashedPassword = hashEncoder.encode(passwordChangeRequest.newPassword)
        ))

        //All tokens invalidated
        refreshTokenRepository.deleteByUserId(user.id)

    }

    /**
     * Reset password via email token (no old password required)
     */
    fun resetPassword(userId: ObjectId, newPassword: String) {
        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        }

        userRepository.save(user.copy(
            hashedPassword = hashEncoder.encode(newPassword)
        ))

        //All tokens invalidated
        refreshTokenRepository.deleteByUserId(user.id)
    }



    /**
     * Serialize a user into a specific response according to the friendship status
     * @param User the user to be serialized
     * @param requestingUserId the user which requested the serialisation
     */
    private fun serializeSyncUser(user: User, requestingUserId : ObjectId, friendshipStatus: FriendshipStatus?, requesterId: ObjectId?, lastChangedAt: Long? = null, nickName: String? = null, shareLocation: Boolean = false, shareSpeedHeading: Boolean = false, shareSnailTrail: Boolean = false): UserResponse {
        //User requests his own data
        if (requestingUserId == user.id) {
            return UserResponse.SelfUserResponse(
                id = user.id.toString(),
                username = user.username,
                userDescription = user.userDescription,
                userStatus = user.userStatus,
                updatedAt = lastChangedAt ?: user.updatedAt.toEpochMilliseconds(),
                birthDate = user.birthDate,
                email = user.email,
                createdAt = user.createdAt.toEpochMilliseconds(),
                emailVerifiedAt = user.emailVerifiedAt?.toEpochMilliseconds(),
                profilePicUpdatedAt = user.profilePicUpdatedAt.toEpochMilliseconds(),
                locationShared = friendsLookupService.hasActiveLocationSharing(user.id),
            )
        }

        //User requests friends data
        else if (friendshipStatus == FriendshipStatus.ACCEPTED) {
            return UserResponse.FriendUserResponse(
                id = user.id.toString(),
                username = user.username,
                userDescription = user.userDescription,
                userStatus = user.userStatus,
                updatedAt = lastChangedAt ?: user.updatedAt.toEpochMilliseconds(),
                birthDate = user.birthDate,
                requesterId = requesterId?.toHexString(),
                profilePicUpdatedAt = user.profilePicUpdatedAt.toEpochMilliseconds(),
                nickName = nickName,
                shareLocation = shareLocation,
                shareSpeedHeading = shareSpeedHeading,
                shareSnailTrail = shareSnailTrail,
                lastSeen = user.lastSeen.toEpochMilliseconds(),
            )
        }

        //User requests random other users data
        else {
            return UserResponse.SimpleUserResponse(
                id = user.id.toString(),
                username = user.username,
                updatedAt = lastChangedAt ?: user.updatedAt.toEpochMilliseconds(),
                friendShipStatus = friendshipStatus,
                requesterId = requesterId?.toHexString(),
                profilePicUpdatedAt = user.profilePicUpdatedAt.toEpochMilliseconds(),
            )
        }
    }


    /**
     * Function to delete a user account with all messages etc
     */
    fun deleteAccount(userId: ObjectId) {
        val user = userLookupService.findById(userId) ?: return

        // Notify friends before deleting friendships (list would be empty after)
        notificationService.notifyUserUpdate(user, deleted = true)

        //Remove all refreshtokens
        refreshTokenRepository.deleteByUserId(userId)

        //Delete all friendships
        val frienduserids = friendsLookupService.getFriends(userId)
        frienduserids.forEach { friendId ->
            val friendship = friendsLookupService.findFriendship(userId, friendId)!!

            friendsSettingsService.deleteFriendshipSettingById(friendship.id)
            friendsLookupService.deleteFriendshipEntry(friendship)
        }

        //Leave all groups
        groupLookupService.leaveAllGroups(userId)

        //delete all user messages
        messageLookupService.deleteAllUserMessages(userId)

        //delete user
        userLookupService.deleteUser(userId)
    }


}