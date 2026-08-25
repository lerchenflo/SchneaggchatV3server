@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.authentication

import com.lerchenflo.schneaggchatv3server.authentication.model.RefreshToken
import com.lerchenflo.schneaggchatv3server.core.security.HashEncoder
import com.lerchenflo.schneaggchatv3server.core.security.JwtService
import com.lerchenflo.schneaggchatv3server.repository.RefreshTokenRepository
import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.user.usermodel.PersonalUserSettings
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import com.lerchenflo.schneaggchatv3server.util.*
import org.bson.types.ObjectId
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
import kotlin.jvm.optionals.getOrNull
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

    fun register(username: String, password: String, email: String, birthdate: String, profilePic: MultipartFile, phoneNumber: String? = null, language: String? = null) : User {

        require(ValidationUtils.validateUsername(username)) { "Username invalid" }
        require(ValidationUtils.validatePassword(password)) { "Password invalid" }
        require(ValidationUtils.validateEmail(email)) { "Email invalid" }
        require(ValidationUtils.validateBirthdate(birthdate)) { "Birthdate invalid" }
        require(ValidationUtils.validatePicture(profilePic)) { "Picture invalid" }
        phoneNumber?.let {
            require(ValidationUtils.validatePhoneNumber(phoneNumber)) { "Phone number invalid" }
        }
        require(language == null || language.length <= 40) { "Language invalid" }

        userLookupService.checkExistingUser(username, email)

        val now = Clock.System.now()

        val user = User(
            username = username.trim().lowercase(getDefault()),
            hashedPassword = hashEncoder.encode(password),
            email = email,
            userDescription = "",
            userStatus = "",
            birthDate = birthdate,
            phoneNumber = phoneNumber?.ifBlank { null },
            createdAt = now,
            updatedAt = now,
            settings = language?.ifBlank { null }?.let { PersonalUserSettings(language = it) }
                ?: PersonalUserSettings()
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
        val now = Clock.System.now()

        //Check if the token is in a correct format and issued by this server
        if (!jwtService.validateRefreshToken(refreshToken)) {
            throw ResponseStatusException(HttpStatusCode.valueOf(401) ,"Invalid refresh token")
        }

        //Get the userid contained in the token (User this token was issued to)
        val userId = jwtService.getUserIdFromToken(refreshToken)

        //find the user to the userid (If no user is found exception is thrown)
        val user = userLookupService.findById(userId)
            ?: throw ResponseStatusException(HttpStatusCode.valueOf(401) ,"Invalid refresh token")

        //Create a hash from the token to compare to the db entry
        val oldTokenHashed = hashToken(refreshToken)

        //Get newest token for this user
        val oldTokenEntry = refreshTokenRepository.findByUserIdAndHashedToken(
            userId = user.id,
            hashedToken = oldTokenHashed,
        ).maxByOrNull {
            it.createdAt //Sort by created timestamp
        }

        if (oldTokenEntry == null) {
            AppLogger.debug("TOKENREFRESH: Old token for user ${user.username} not found")
            throw ResponseStatusException(HttpStatusCode.valueOf(401), "Invalid refresh token")
        }

        //Check if the token refresh was already executed with this one, if true get the new token
        if (oldTokenEntry.deletedAt != null && oldTokenEntry.replacedByToken != null /* Needed for migration if old tokens do not have a replacedby set*/) {
            val newToken = refreshTokenRepository.findById(oldTokenEntry.replacedByToken).getOrNull()

            //Check the new token
            if (newToken != null && //Token exists (Should always be)
                newToken.deletedAt == null && //New token not deleted (If deleted, token was already rotated once again)
                newToken.rawToken != null //The raw token is still saved in the db (May not be saved during migration)
                ) {

                AppLogger.success("User failed the sync but got the linked token")

                return TokenPair(
                    accessToken = jwtService.generateAccessToken(userId),
                    refreshToken = newToken.rawToken,
                    encryptionKey = jwtService.getEncryptionKey()
                )
            }

            //At this point the token was either old (not migrated) or already deleted, throw an exception
            throw ResponseStatusException(HttpStatusCode.valueOf(401), "Invalid refresh token")
        }

        //The token is working normally, return new
        val newAccessToken = jwtService.generateAccessToken(userId)
        val newRefreshToken = jwtService.generateRefreshToken(userId)

        //Store the new refresh token
        val newTokenEntry = storeRefreshToken(user.id, newRefreshToken)


        val query = Query().addCriteria(
            Criteria.where("userId").`is`(user.id)
                .and("hashedToken").`is`(oldTokenHashed)
                .and("deletedAt").`is`(null)
        )

        val update = Update()
            .set("deletedAt", now)
            .set("replacedByToken", newTokenEntry.id)
            .set("rawToken", null) //New token is in place, delete the old raw entry


        // Returns the document BEFORE the update — null if already claimed
        val claimedToken = mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(false),
            RefreshToken::class.java
        )

        // Lost the race to claim the old token — most likely a concurrent refresh request for the
        // SAME old token that got there first. Try to recover the token it rotated to, instead of
        // forcing this caller to 401 / re-login.
        if (claimedToken == null) {
            refreshTokenRepository.delete(newTokenEntry) //Remove new unused token

            val rotated = refreshTokenRepository.findById(oldTokenEntry.id).getOrNull()
            val linked = rotated?.replacedByToken?.let { refreshTokenRepository.findById(it).getOrNull() }

            if (linked != null && linked.deletedAt == null && linked.rawToken != null) {
                AppLogger.success("Concurrent refresh: returned winner's rotated token for user ${user.username}")

                return TokenPair(
                    accessToken = jwtService.generateAccessToken(userId),
                    refreshToken = linked.rawToken,
                    encryptionKey = jwtService.getEncryptionKey()
                )
            }

            //At this point the token was either old (not migrated) or already deleted, throw an exception
            AppLogger.warn("TOKENREFRESH REPLAY?: Token was already deleted for user ${user.username}")
            throw ResponseStatusException(HttpStatusCode.valueOf(401), "Invalid refresh token")
        }

        return TokenPair(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
            encryptionKey = jwtService.getEncryptionKey()
        )
    }


    private fun storeRefreshToken(userId: ObjectId, rawRefreshToken: String): RefreshToken {

        val hashed = hashToken(rawRefreshToken)
        val expiryMs = jwtService.refreshTokenValidityMs
        val expiresAt = Clock.System.now().toEpochMilliseconds() + expiryMs

        return refreshTokenRepository.save(
            RefreshToken(
                userId = userId,
                hashedToken = hashed,
                rawToken = rawRefreshToken,
                expiresAt = Instant.fromEpochMilliseconds(expiresAt),
            )
        )
    }

    private fun hashToken(token: String) : String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashedBytes = digest.digest(token.encodeToByteArray())
        return Base64.getEncoder().encodeToString(hashedBytes)
    }



}