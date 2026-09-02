@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user

import com.lerchenflo.schneaggchatv3server.authentication.EmailService
import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.notifications.apns.ApnsService
import com.lerchenflo.schneaggchatv3server.notifications.firebase.FirebaseService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsService
import com.lerchenflo.schneaggchatv3server.user.usermodel.NewFriendsUserResponse
import com.lerchenflo.schneaggchatv3server.user.usermodel.PinnedChat
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserRequest
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.Locale.getDefault
import kotlin.time.ExperimentalTime

@RestController
@RequestMapping("/users")
class UserController(
    private val userService: UserService,
    private val friendshipsService: FriendsService,
    private val emailService: EmailService,

    private val firebaseService: FirebaseService,
    private val apnsService: ApnsService,
) {

    @PostMapping("/verificationemail")
    fun sendVerificationEmail(){
        val requestingUserId = requireAuth()

        emailService.sendVerificationEmail(requestingUserId)
    }


    data class NotificationTokenRequest(
        @field:NotBlank(message = "Token must not be blank")
        @field:Size(max = 2000, message = "Token too long")
        val token: String,
        val isAndroid: Boolean
    )

    @PostMapping("/setnotificationtoken")
    fun setNotificationToken(
        @Valid @RequestBody request: NotificationTokenRequest
    ) {
        require(ValidationUtils.validateNotificationToken(request.token, request.isAndroid)) { "Invalid notification token" }

        val requestingUserId = requireAuth()

        //AppLogger.debug("Setting notification token: Android:${request.isAndroid}} ${request.token}")

        val userId = requestingUserId
        if (request.isAndroid) firebaseService.saveToken(userId = userId, token = request.token)
        else apnsService.saveToken(userId = userId, token = request.token)
    }


    @PostMapping("/changeusername")
    fun changeUsername(
        @RequestBody(required = true) newUsername: String,
    ){
        val requestingUserId = requireAuth()

        userService.changeUsername(requestingUserId, newUsername.trim().lowercase(getDefault()))
    }

    @PostMapping("/changepassword")
    fun changePassword(
        @Valid @RequestBody(required = true) changeRequest: UserService.PasswordChangeRequest,
    ){
        val requestingUserId = requireAuth()

        userService.changePassword(
            requestingUserId = requestingUserId,
            passwordChangeRequest = changeRequest
        )
    }





    @PostMapping("/sync")
    fun syncUsers(
        @RequestBody requestBody: List<UserService.IdTimeStamp>
    ) : UserService.UserSyncResponse {
        val requestingUserId = requireAuth()

        return userService.userIdSync(
            idTimeStamps = requestBody,
            requesterId = requestingUserId,
        )
    }





    //TODO: Check user profilepic settings (implement first)
    @GetMapping("/profilepic/{id}")
    fun getProfilePic(@PathVariable("id") userId: String): ResponseEntity<ByteArray> {
        require(ValidationUtils.validateObjectId(userId)) { "Invalid user ID" }
        val requestingUserId = requireAuth()

        val imageBytes = userService.getProfilePic(requestingUserId, ObjectId(userId))
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .body(imageBytes)
    }

    @PostMapping("/setprofilepic")
    fun setProfilePic(
        @RequestParam("profilepic") multipartFile: MultipartFile
    ) {
        val requestingUserId = requireAuth()

        userService.changeProfilepic(
            requestingUserId = requestingUserId,
            newPic = multipartFile
        )
    }


    @PostMapping("/changeprofile")
    fun changeProfile(
        @Valid @RequestBody request: UserRequest
    ) {
        val requestingUserId = requireAuth()

        userService.changeUserProfile(
            changingUserId = requestingUserId,
            userRequest = request
        )
    }


    data class LocationShareRequest(
        val friendId: String,

        val share: Boolean = false,              // share location at all
        val shareSpeedHeading: Boolean = false,  // share speed + heading together
        val shareSnailTrail: Boolean = false,    // share snail trail (full 24h history)
    )

    @PostMapping("/sharelocation")
    fun setLocationSharing(
        @RequestBody request: LocationShareRequest
    ) {
        require(ValidationUtils.validateObjectId(request.friendId)) { "Invalid friend ID" }
        val requestingUserId = requireAuth()

        friendshipsService.setLocationSharing(
            userId = requestingUserId,
            friendId = ObjectId(request.friendId),
            share = request.share,
            shareSpeedHeading = request.shareSpeedHeading,
            shareSnailTrail = request.shareSnailTrail,
        )
    }


    data class WakePermissionRequest(
        val friendId: String,
        val allowWake: Boolean = false, // may this friend wake the requesting user
    )

    /**
     * Set whether a single friend is allowed to wake the requesting user.
     * The setting always belongs to the caller, so a user can only ever change who may wake
     * themselves - never who may wake someone else.
     */
    @PostMapping("/setwakepermission")
    fun setWakePermission(
        @RequestBody request: WakePermissionRequest
    ) {
        require(ValidationUtils.validateObjectId(request.friendId)) { "Invalid friend ID" }
        val requestingUserId = requireAuth()

        friendshipsService.setWakePermission(
            userId = requestingUserId,
            friendId = ObjectId(request.friendId),
            allowWake = request.allowWake,
        )
    }


    data class WakeGlobalRequest(
        val enabled: Boolean = false,
    )

    /**
     * Master switch for the wake feature. While off, nobody can wake the requesting user
     * regardless of the per-friend permissions.
     */
    @PostMapping("/setwakeglobal")
    fun setWakeGlobal(
        @RequestBody request: WakeGlobalRequest
    ) {
        val requestingUserId = requireAuth()
        userService.setWakeGlobal(requestingUserId, request.enabled)
    }


    /**
     * Partial update of the caller's own [com.lerchenflo.schneaggchatv3server.user.usermodel.PersonalUserSettings].
     * All fields nullable - a null field means "leave unchanged", so a client only ever sends the
     * settings it actually changed. The user id always comes from [requireAuth], never the body.
     */
    data class UserSettingsRequest(
        val mdFormat: Boolean? = null,
        val highlightTodaysMessageTimestamp: Boolean? = null,
        @field:Size(max = 40, message = "Theme too long")
        val theme: String? = null,
        @field:Size(max = 40, message = "Language too long")
        val language: String? = null,
        val mergeMapLocations: Boolean? = null,
        val mergeMapUsers: Boolean? = null,
        @field:Size(max = 40, message = "Map style too long")
        val mapStyle: String? = null,
        val pinnedChats: List<PinnedChat>? = null,
        val developerSettings: Boolean? = null,
        val lastContributePopupShown: Long? = null,
    )

    @PostMapping("/settings")
    fun updateSettings(
        @Valid @RequestBody request: UserSettingsRequest
    ) {
        val requestingUserId = requireAuth()

        userService.updateSettings(
            userId = requestingUserId,
            mdFormat = request.mdFormat,
            highlightTodaysMessageTimestamp = request.highlightTodaysMessageTimestamp,
            theme = request.theme,
            language = request.language,
            mergeMapLocations = request.mergeMapLocations,
            mergeMapUsers = request.mergeMapUsers,
            mapStyle = request.mapStyle,
            pinnedChats = request.pinnedChats,
            developerSettings = request.developerSettings,
            lastContributePopupShown = request.lastContributePopupShown,
        )
    }


    @GetMapping("/availableusers")
    fun getAvailableUsers(
        @RequestParam("searchterm", required = false) searchTerm: String?,
    ) : List<NewFriendsUserResponse> {
        if (searchTerm != null) {
            require(ValidationUtils.validateSearchTerm(searchTerm)) { "Search term too long" }
        }

        val requestingUserId = requireAuth()


        return userService.getAvailableUsers(
            searchTerm = searchTerm?.trim()?.lowercase(getDefault()),
            requestingUserId = requestingUserId
        )

    }


    @PostMapping("/addfriend/{id}")
    fun sendFriendRequest(
        @PathVariable("id") touserId: String
    ) {
        require(ValidationUtils.validateObjectId(touserId)) { "Invalid user ID" }
        val requestingUserId = requireAuth()

        friendshipsService.sendFriendRequest(
            fromUserId = requestingUserId,
            toUserId = ObjectId(touserId)
        )
    }

    @PostMapping("/denyfriend/{id}")
    fun denyFriendRequest(
        @PathVariable("id") touserId: String
    ) {
        require(ValidationUtils.validateObjectId(touserId)) { "Invalid user ID" }
        val requestingUserId = requireAuth()


        friendshipsService.declineFriendRequest(
            requestingUserId,
            ObjectId(touserId)
        )
    }

    @PostMapping("/removefriend/{id}")
    fun removeFriend(
        @PathVariable("id") removedfriend: String
    ) {
        require(ValidationUtils.validateObjectId(removedfriend)) { "Invalid user ID" }
        val requestingUserId = requireAuth()


        friendshipsService.removeFriend(
            userId = requestingUserId,
            friendId = ObjectId(removedfriend)
        )
    }

}