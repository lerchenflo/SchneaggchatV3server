@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.user

import com.lerchenflo.schneaggchatv3server.authentication.EmailService
import com.lerchenflo.schneaggchatv3server.notifications.apns.ApnsService
import com.lerchenflo.schneaggchatv3server.notifications.firebase.FirebaseService
import com.lerchenflo.schneaggchatv3server.user.friends.FriendsService
import com.lerchenflo.schneaggchatv3server.user.usermodel.NewFriendsUserResponse
import com.lerchenflo.schneaggchatv3server.user.usermodel.UserRequest
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.ImageManager
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.Locale
import java.util.Locale.getDefault
import kotlin.time.ExperimentalTime

@RestController
@RequestMapping("/users")
class UserController(
    private val userService: UserService,
    private val friendshipsService: FriendsService,
    private val emailService: EmailService,
    private val imageManager: ImageManager,


    private val firebaseService: FirebaseService,
    private val apnsService: ApnsService,
) {

    @PostMapping("/verificationemail")
    fun sendVerificationEmail(){
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        emailService.sendVerificationEmail(ObjectId(requestingUserId))
    }


    //TODO: Input validation
    data class NotificationTokenRequest(
        val token: String,
        val isAndroid: Boolean
    )

    @PostMapping("/setnotificationtoken")
    fun setNotificationToken(
        @RequestBody request: NotificationTokenRequest
    ) {
        require(ValidationUtils.validateNotificationToken(request.token, request.isAndroid)) { "Invalid notification token" }

        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        AppLogger.debug("Setting notification token: Android:${request.isAndroid}} ${request.token}")

        val userId = ObjectId(requestingUserId)
        if (request.isAndroid) firebaseService.saveToken(userId = userId, token = request.token)
        else apnsService.saveToken(userId = userId, token = request.token)
    }


    @PostMapping("/changeusername")
    fun changeUsername(
        @RequestBody(required = true) newUsername: String,
    ){
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in")

        userService.changeUsername(requestingUserId, newUsername.trim().lowercase(getDefault()))
    }

    @PostMapping("/changepassword")
    fun changePassword(
        @Valid @RequestBody(required = true) changeRequest: UserService.PasswordChangeRequest,
    ){
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in")

        userService.changePassword(
            requestingUserId = requestingUserId,
            passwordChangeRequest = changeRequest
        )
    }







    @PostMapping("/sync")
    fun syncUsers(
        @RequestBody requestBody: List<UserService.IdTimeStamp>
    ) : UserService.UserSyncResponse {
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        return userService.userIdSync(
            idTimeStamps = requestBody,
            requesterId = ObjectId(requestingUserId),
        )
    }





    //TODO: Check user profilepic settings (implement first)
    @GetMapping("/profilepic/{id}")
    fun getProfilePic(@PathVariable("id") userId: String): ResponseEntity<ByteArray> {
        require(ValidationUtils.validateObjectId(userId)) { "Invalid user ID" }
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        //TODO: Move into userservice
        return try {
            val imageName = imageManager.getProfilePicFileName(userId, false)
            val imageBytes = imageManager.loadProfilePicFromFile(imageName)
            ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(imageBytes)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.notFound().build()
        }
    }

    @PostMapping("/setprofilepic")
    fun setProfilePic(
        @RequestParam("profilepic") multipartFile: MultipartFile
    ) {
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        userService.changeProfilepic(
            requestingUserId = requestingUserId,
            newPic = multipartFile
        )
    }


    @PostMapping("/changeprofile")
    fun changeProfile(
        @Valid @RequestBody request: UserRequest
    ) {
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        userService.changeUserProfile(
            changingUserId = requestingUserId,
            userRequest = request
        )
    }


    @GetMapping("/availableusers")
    fun getAvailableUsers(
        @RequestParam("searchterm", required = false) searchTerm: String?,
    ) : List<NewFriendsUserResponse> {
        if (searchTerm != null) {
            require(ValidationUtils.validateSearchTerm(searchTerm)) { "Search term too long" }
        }

        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )


        return userService.getAvailableUsers(
            searchTerm = searchTerm?.trim()?.lowercase(getDefault()),
            requestingUserId = requestingUserId
        )

    }


    // TODO: These friendship mutation endpoints should use POST, not GET (REST correctness; safe currently since JWT is in header, not cookie)
    @GetMapping("/addfriend/{id}")
    fun sendFriendRequest(
        @PathVariable("id") touserId: String
    ) {
        require(ValidationUtils.validateObjectId(touserId)) { "Invalid user ID" }
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        val friendship = friendshipsService.sendFriendRequest(
            fromUserId = ObjectId(requestingUserId),
            toUserId = ObjectId(touserId)
        )

        //println("Friend request: $friendship")
    }

    @GetMapping("/denyfriend/{id}")
    fun denyFriendRequest(
        @PathVariable("id") touserId: String
    ) {
        require(ValidationUtils.validateObjectId(touserId)) { "Invalid user ID" }
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        friendshipsService.declineFriendRequest(
            ObjectId(requestingUserId),
            ObjectId(touserId)
        )
    }

    @GetMapping("/removefriend/{id}")
    fun removeFriend(
        @PathVariable("id") removedfriend: String
    ) {
        require(ValidationUtils.validateObjectId(removedfriend)) { "Invalid user ID" }
        val requestingUserId =
            SecurityContextHolder.getContext().authentication?.principal as? String ?: throw ResponseStatusException(
                /* status = */ HttpStatus.FORBIDDEN,
                /* reason = */ "Not logged in"
            )

        friendshipsService.removeFriend(
            userId = ObjectId(requestingUserId),
            friendId = ObjectId(removedfriend)
        )
    }

}