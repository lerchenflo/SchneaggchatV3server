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

    fun login(username: String, password: String, deviceName: String, devicetype: AuthController.DEVICETYPE) : TokenPair {

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

        // Login dedup: reuse this device's existing session row (rotate it in place) instead of
        // inserting a second one, so the collection keeps exactly one row per logged-in device.
        // Blank device names are never dedup'd - they can't identify a device. If the in-place
        // rotation loses a (very unlikely) race with a concurrent refresh of the same row, fall
        // back to inserting a fresh row; the duplicate ages out via the expiresAt sweep.
        val existing = if (deviceName.isNotBlank()) {
            refreshTokenRepository.findFirstByUserIdAndDeviceNameAndDeviceTypeOrderByCreatedAtDesc(
                userId = user.id,
                deviceName = deviceName,
                deviceType = devicetype,
            )
        } else null

        if (existing == null || rotateTokenRow(user.id, existing.hashedToken, newRefreshToken, deviceName, devicetype) == null) {
            storeRefreshToken(
                userId = user.id,
                rawRefreshToken = newRefreshToken,
                deviceName = deviceName,
                devicetype = devicetype,
            )
        }

        return TokenPair(
            accessToken = newAccessToken,
            refreshToken = newRefreshToken,
            encryptionKey = jwtService.getEncryptionKey()
        )
    }


    fun refresh(refreshToken: String, deviceName: String, devicetype: AuthController.DEVICETYPE) : TokenPair {

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

        val newRefreshToken = jwtService.generateRefreshToken(userId)

        // Atomically rotate this device's session row in place: hashedToken -> new hash,
        // previousHashedToken -> the hash just presented. Exactly one concurrent caller can win
        // this findAndModify; everyone else falls through to replay recovery below.
        val claimed = rotateTokenRow(user.id, oldTokenHashed, newRefreshToken, deviceName, devicetype)

        if (claimed != null) {
            return TokenPair(
                accessToken = jwtService.generateAccessToken(userId),
                refreshToken = newRefreshToken,
                encryptionKey = jwtService.getEncryptionKey()
            )
        }

        // No row holds this hash as its current token. Either the client lost a previous refresh
        // response (or lost the race against a concurrent refresh) and is replaying the token
        // that was already rotated away - then a row still holds it as previousHashedToken and we
        // return that row's current raw token - or the token is genuinely dead (logged out,
        // expired row swept, password changed) and the 401 below is correct.
        val recovery = refreshTokenRepository.findByUserIdAndPreviousHashedToken(
            userId = user.id,
            previousHashedToken = oldTokenHashed,
        ).maxByOrNull { it.createdAt }

        if (recovery?.rawToken != null) {
            AppLogger.success("TOKENREFRESH replay: returned current token for user ${user.username}")

            return TokenPair(
                accessToken = jwtService.generateAccessToken(userId),
                refreshToken = recovery.rawToken,
                encryptionKey = jwtService.getEncryptionKey()
            )
        }

        AppLogger.warn("TOKENREFRESH: Unknown token for user ${user.username}")
        throw ResponseStatusException(HttpStatusCode.valueOf(401), "Invalid refresh token")
    }

    /**
     * Atomic in-place rotation of the session row whose current hash is [expectedCurrentHash].
     * Returns the row as it was BEFORE the update, or null if no row matched (someone else
     * rotated it first, or it never existed). Sliding expiry: every rotation pushes
     * [RefreshToken.expiresAt] out by the full refresh validity.
     */
    private fun rotateTokenRow(
        userId: ObjectId,
        expectedCurrentHash: String,
        newRawToken: String,
        deviceName: String,
        devicetype: AuthController.DEVICETYPE,
    ): RefreshToken? {
        val query = Query().addCriteria(
            Criteria.where("userId").`is`(userId)
                .and("hashedToken").`is`(expectedCurrentHash)
        )

        val update = Update()
            .set("previousHashedToken", expectedCurrentHash)
            .set("hashedToken", hashToken(newRawToken))
            .set("rawToken", newRawToken)
            .set("expiresAt", Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() + jwtService.refreshTokenValidityMs))
            .set("deviceName", deviceName)
            .set("deviceType", devicetype)
            // Transitional: a pre-migration soft-deleted row can still hold this hash as its
            // current token; claiming it revives it, so strip the legacy soft-delete markers or
            // MainController.migrateRefreshTokenChains would sweep the revived row. No-op on
            // rows written after the migration.
            .unset("deletedAt")
            .unset("replacedByToken")

        return mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(false),
            RefreshToken::class.java
        )
    }


    private fun storeRefreshToken(userId: ObjectId, rawRefreshToken: String, deviceName: String, devicetype: AuthController.DEVICETYPE): RefreshToken {

        val hashed = hashToken(rawRefreshToken)
        val expiryMs = jwtService.refreshTokenValidityMs
        val expiresAt = Clock.System.now().toEpochMilliseconds() + expiryMs

        return refreshTokenRepository.save(
            RefreshToken(
                userId = userId,
                hashedToken = hashed,
                rawToken = rawRefreshToken,
                expiresAt = Instant.fromEpochMilliseconds(expiresAt),
                deviceName = deviceName,
                deviceType = devicetype,
            )
        )
    }

    private fun hashToken(token: String) : String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashedBytes = digest.digest(token.encodeToByteArray())
        return Base64.getEncoder().encodeToString(hashedBytes)
    }



}