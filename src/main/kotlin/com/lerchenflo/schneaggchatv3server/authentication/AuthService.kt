@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.authentication

import com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken
import com.lerchenflo.schneaggchatv3server.core.security.HashEncoder
import com.lerchenflo.schneaggchatv3server.core.security.JwtService
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.util.*
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.http.HttpStatusCode
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.*
import java.util.Locale.getDefault
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Service
class AuthService(
    private val jwtService: JwtService,
    private val userLookupService: UserLookupService,

    private val hashEncoder: HashEncoder,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val loggingService: LoggingService,
    private val imageManager: ImageManager,

    private val mongoTemplate: MongoTemplate,
) {

    data class TokenPair(
        val accessToken: String,
        val refreshToken: String,
        val encryptionKey: String? = null
    )

    fun register(username: String, password: String, email: String, birthdate: String, profilePic: MultipartFile) : User {

        require(ValidationUtils.validateUsername(username)) { "Username invalid" }
        require(ValidationUtils.validatePassword(password)) { "Password invalid" }
        require(ValidationUtils.validateEmail(email)) { "Email invalid" }
        require(ValidationUtils.validateBirthdate(birthdate)) { "Birthdate invalid" }
        require(ValidationUtils.validatePicture(profilePic)) { "Picture invalid" }

        userLookupService.checkExistingUser(username, email)

        val now = Clock.System.now()

        val user = User(
            username = username.trim().lowercase(getDefault()),
            hashedPassword = hashEncoder.encode(password),
            email = email,
            userDescription = "",
            userStatus = "",
            birthDate = birthdate,
            createdAt = now,
            updatedAt = now
        )

        //Save users profilepicture
        imageManager.saveProfilePic(
            image = profilePic,
            userId = user.id.toHexString(),
            group = false
        )

        return userLookupService.save(user)
    }

    fun login(username: String, password: String) : TokenPair {

        //Does this user exist
        val user = userLookupService.findByUsername(username) ?: run {
            throw BadCredentialsException("Invalid credentials")
        }

        loggingService.log(
            userId = user.id,
            logType = LogType.USER_LOGIN
        )

        //Does the password match
        if (!hashEncoder.matches(password, user.hashedPassword)) {
            throw BadCredentialsException("Invalid credentials")
        }

        //Valid credentials entered
        val newAccessToken = jwtService.generateAccessToken(user.id.toHexString())
        val newRefreshToken = jwtService.generateRefreshToken(user.id.toHexString())


        storeRefreshToken(
            userId = user.id,
            rawRefreshToken = newRefreshToken,
        )

        return TokenPair(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
            encryptionKey = jwtService.getEncryptionKey()
        )
    }


    fun refresh(refreshToken: String) : TokenPair {

        //Check if the token is in a correct format and issued by this server
        if (!jwtService.validateRefreshToken(refreshToken)) {
            throw ResponseStatusException(HttpStatusCode.valueOf(401) ,"Invalid refresh token")
        }

        //Get the userid contained in the token (User this token was issued to)
        val userId = jwtService.getUserIdFromToken(refreshToken)

        //find the user to the userid (If no user is found exception is thrown)
        val user = userLookupService.findById(userId)
            ?: throw ResponseStatusException(HttpStatusCode.valueOf(401) ,"Invalid refresh token")

        val now = Clock.System.now()

        //Create a hash from the token to compare to the db entry
        val hashed = hashToken(refreshToken)

        val query = Query().addCriteria(
            Criteria.where("userId").`is`(user.id)
                .and("hashedToken").`is`(hashed)
                .and("deletedAt").`is`(null)
        )

        val update = Update().set("deletedAt", now)

        // Returns the document BEFORE the update — null if already claimed
        val claimedToken = mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(false),
            RefreshToken::class.java
        )

        if (claimedToken == null) {
            // Deleted too long ago — likely a replay attack
            AppLogger.warn("TOKENREFRESH: Token was already deleted for user ${user.username}")
            throw ResponseStatusException(HttpStatusCode.valueOf(401), "Invalid refresh token")
        }


        val newAccessToken = jwtService.generateAccessToken(userId)
        val newRefreshToken = jwtService.generateRefreshToken(userId)

        //Try to save the new token, if another token was already saved in the meantime, remove the change
        try {
            storeRefreshToken(user.id, newRefreshToken)
        } catch (e: DuplicateKeyException) {

            //Restore the just deleted token that the client can use it again
            mongoTemplate.save(claimedToken.copy(deletedAt = null))

            throw ResponseStatusException(HttpStatusCode.valueOf(409), "Concurrent refresh - reload tokens")
        }

        return TokenPair(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
            encryptionKey = jwtService.getEncryptionKey()
        )
    }


    private fun storeRefreshToken(userId: ObjectId, rawRefreshToken: String) {

        val hashed = hashToken(rawRefreshToken)
        val expiryMs = jwtService.refreshTokenValidityMs
        val expiresAt = Clock.System.now().toEpochMilliseconds() + expiryMs
        

        try {
            refreshTokenRepository.save(
                RefreshToken(
                    userId = userId,
                    hashedToken = hashed,
                    expiresAt = Instant.fromEpochMilliseconds(expiresAt),
                )
            )
        } catch (e: Exception) {
            throw e
        }
    }

    private fun hashToken(token: String) : String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashedBytes = digest.digest(token.encodeToByteArray())
        return Base64.getEncoder().encodeToString(hashedBytes)
    }



}