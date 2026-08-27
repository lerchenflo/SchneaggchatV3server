package com.lerchenflo.schneaggchatv3server.util

import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeParseException
import java.util.Locale.getDefault

object ValidationUtils {

    private val EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
    private val ALLOWED_IMAGE_TYPES = setOf("image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp")
    private const val MAX_IMAGE_SIZE = 3 * 1024 * 1024 // 3MB

    private val ALLOWED_AUDIO_TYPES = setOf("audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac")
    private const val MAX_AUDIO_SIZE = 2 * 1024 * 1024 // 2MB, roughly 2 minutes of m4a

    private val RESERVED_USERNAMES = setOf(
        "admin", "administrator", "root", "system", "api",
        "www", "mail", "ftp", "localhost", "test", "demo"
    )

    /**
     * Validates email address format
     * - Must follow standard email format
     * - Must not be empty or blank
     */
    fun validateEmail(email: String): Boolean {
        if (email.isBlank()) return false
        if (email.length > 254) return false // RFC 5321
        return EMAIL_REGEX.matches(email.trim())
    }

    /**
     * Validates password strength
     * - Minimum 8 characters
     * - At least one uppercase letter
     * - At least one lowercase letter
     * - At least one digit
     * - At least one special character
     */
    fun validatePassword(password: String): Boolean {
        if (password.length < 8) return false
        if (password.length > 128) return false // Reasonable upper limit

        val hasUpperCase = password.any { it.isUpperCase() }
        val hasLowerCase = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() }

        return hasUpperCase && hasLowerCase && hasDigit && hasSpecial
    }


    /**
     * Validates birthdate format and value
     * - Must follow YYYY-MM-DD format
     * - Must be a valid calendar date
     * - Must not be in the future
     * - User must be at least 8 years old
     * - User must not be older than 120 years
     */
    fun validateBirthdate(birthdate: String): Boolean {
        if (birthdate.isBlank()) return false

        val BIRTHDATE_REGEX = "^\\d{4}-\\d{2}-\\d{2}$".toRegex()
        if (!BIRTHDATE_REGEX.matches(birthdate)) return false

        return try {
            val date = LocalDate.parse(birthdate)
            val today = LocalDate.now()

            if (date.isAfter(today)) return false
            if (Period.between(date, today).years < 8) return false
            if (Period.between(date, today).years > 120) return false

            true
        } catch (e: DateTimeParseException) {
            false
        }
    }

    /**
     * Validates username
     * - Length between 3 and 25 characters
     * - Only alphanumeric characters, underscores, and hyphens
     * - Must start with a letter or number
     * - Cannot end with underscore or hyphen
     */
    fun validateUsername(username: String): Boolean {
        if (username.isEmpty()) return false
        if (username.length !in 3..25) return false

        // Must start with alphanumeric
        if (!username.first().isLetterOrDigit()) return false

        if (!username.last().isLetterOrDigit()) return false


        if (username.lowercase(getDefault()) in RESERVED_USERNAMES) return false

        // Only alphanumeric, underscore, and hyphen allowed
        val validChars = username.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == ' ' }

        return validChars
    }

    //User can name his friends however he wants
    fun validateNickname(nickname: String): Boolean {
        //if (nickname.isEmpty()) return false

        return true
    }

    /**
     * Validates a phone number, loosely.
     * - Blank is allowed (used to clear the stored number)
     * - Allows digits, spaces, +, -, /, (, ), .
     * - At most one leading +
     * - Stripped of separators, must be 6-20 digits
     */
    private val PHONE_ALLOWED_CHARS_REGEX = "^\\+?[0-9 \\-/().]*$".toRegex()
    fun validatePhoneNumber(phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return true

        if (phoneNumber.length > 25) return false
        if (!PHONE_ALLOWED_CHARS_REGEX.matches(phoneNumber)) return false

        val digitsOnly = phoneNumber.filter { it.isDigit() }
        return digitsOnly.length in 6..20
    }


    /**
     * Validates uploaded profile picture
     * - File must not be empty
     * - Must be an image type (JPEG, PNG, GIF, WebP)
     * - Maximum size: 3MB
     * - Must have valid content type
     */
    fun validatePicture(picture: MultipartFile): Boolean {
        // Check if file is empty
        if (picture.isEmpty) return false

        // Check file size
        if (picture.size > MAX_IMAGE_SIZE) return false

        // Check content type
        val contentType = picture.contentType?.lowercase() ?: return false
        if (contentType !in ALLOWED_IMAGE_TYPES) return false

        // Check original filename exists and has valid extension
        val filename = picture.originalFilename ?: return false
        val extension = filename.substringAfterLast('.', "").lowercase()
        val validExtensions = setOf("jpg", "jpeg", "png", "gif", "webp")
        if (extension !in validExtensions) return false

        return true
    }

    fun validateDescription(string: String) : Boolean {
        if (string.length > 200) return false

        return true
    }

    fun validateEventTitle(title: String): Boolean {
        if (title.isBlank()) return false
        if (title.length > 200) return false

        return true
    }

    fun validateEventMaxUsers(maxUsers: Int?): Boolean {
        if (maxUsers == null) return true
        if (maxUsers < 1 || maxUsers > 1000) return false

        return true
    }

    fun validateStringMessage(string: String) : Boolean {
        if (string.length > 10000 || string.isEmpty()) return false

        return true
    }

    fun validateReactionContent(content: String): Boolean {
        if (content.isEmpty() || content.length > 10) return false
        return true
    }

    fun validatePollVoteText(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.length > 250) return false

        return true
    }

    fun validatePollTitle(title: String): Boolean {
        if (title.isBlank()) return false
        if (title.length > 200) return false

        return true
    }

    fun validatePollDescription(description: String?): Boolean {
        if (description == null) return true
        if (description.length > 500) return false

        return true
    }

    /**
     * Validates an uploaded audio message file
     * - File must not be empty
     * - Must be an accepted audio type (m4a/aac)
     * - Maximum size: 2MB
     * - Must have valid content type and extension
     */
    fun validateAudio(audio: MultipartFile): Boolean {
        if (audio.isEmpty) return false

        if (audio.size > MAX_AUDIO_SIZE) return false

        val contentType = audio.contentType?.lowercase() ?: return false
        if (contentType !in ALLOWED_AUDIO_TYPES) return false

        val filename = audio.originalFilename ?: return false
        val extension = filename.substringAfterLast('.', "").lowercase()
        val validExtensions = setOf("m4a", "mp4", "aac")
        if (extension !in validExtensions) return false

        return true
    }

    /**
     * Validates MongoDB ObjectId string format
     * - Must be exactly 24 hexadecimal characters
     */
    private val OBJECT_ID_REGEX = "^[a-fA-F0-9]{24}$".toRegex()
    fun validateObjectId(id: String): Boolean {
        return OBJECT_ID_REGEX.matches(id)
    }

    /**
     * Validates a latitude/longitude pair
     * - Latitude must be between -90 and 90
     * - Longitude must be between -180 and 180
     * - Neither value may be NaN or infinite
     */
    fun validateLatLong(lat: Double, long: Double): Boolean {
        if (lat.isNaN() || long.isNaN() || lat.isInfinite() || long.isInfinite()) return false
        if (lat < -90.0 || lat > 90.0) return false
        if (long < -180.0 || long > 180.0) return false
        return true
    }

    /**
     * Validates token strings (JWT, email verification, etc.)
     * - Must not be blank
     * - Maximum 2000 characters
     */
    fun validateToken(token: String): Boolean {
        if (token.isBlank()) return false
        if (token.length > 2000) return false
        return true
    }

    /**
     * Validates Firebase Cloud Messaging token
     * - Must not be blank
     * - Maximum 500 characters
     */
    fun validateFirebaseToken(token: String): Boolean {
        if (token.isBlank()) return false
        if (token.length > 500) return false
        return true
    }

    /**
     * Validates search term input
     * - Maximum 50 characters
     * - Can be empty (optional search)
     */
    fun validateSearchTerm(term: String): Boolean {
        if (term.length > 50) return false
        return true
    }

    /**
     * Validates pagination page number
     * - Must be non-negative
     */
    fun validatePaginationPage(page: Int): Boolean {
        return page >= 0
    }

    /**
     * Validates pagination page size
     * - Must be between 1 and 1000
     */
    fun validatePaginationPageSize(pageSize: Int): Boolean {
        return pageSize in 1..1000
    }

    /**
     * Validates a `since` version cursor for version-based sync endpoints
     * - Must be non-negative (0 means "never synced")
     */
    fun validateSyncVersion(version: Long): Boolean {
        return version >= 0
    }

    /**
     * Validates a timestamp (epoch millis)
     * - Must be positive
     * - Must not be more than 1 minute in the future
     */
    fun validateTimestamp(timestamp: Long): Boolean {
        if (timestamp <= 0) return false
        val now = System.currentTimeMillis()
        if (timestamp > now + 60_000) return false // 1 minute tolerance
        return true
    }

    /**
     * Validates login input (username/password) - lightweight check
     * - Must not be blank
     * - Maximum 500 characters (prevent large payloads)
     */
    fun validateLoginInput(input: String): Boolean {
        if (input.isBlank()) return false
        if (input.length > 500) return false
        return true
    }

    private val APNS_TOKEN_REGEX = "^[0-9a-fA-F]{64}$".toRegex()

    fun validateNotificationToken(token: String, isAndroid: Boolean): Boolean {
        return if (isAndroid) validateFirebaseToken(token)
        else APNS_TOKEN_REGEX.matches(token)
    }

    /**
     * Validates a speed reading in meters/second.
     * - Must not be NaN/infinite
     * - Must be non-negative, and below a sane upper bound (sanity check, not a real-world limit)
     */
    fun validateSpeed(speed: Double): Boolean {
        if (speed.isNaN() || speed.isInfinite()) return false
        return speed in 0.0..120.0 // ~430 km/h, generous upper bound to reject garbage input
    }

    /**
     * Validates a compass heading in degrees.
     * - Must not be NaN/infinite
     * - Must be in [0, 360]
     */
    fun validateHeading(heading: Double): Boolean {
        if (heading.isNaN() || heading.isInfinite()) return false
        return heading in 0.0..360.0
    }

    /**
     * Validates a battery level percentage.
     * - Must be between 0 and 100 inclusive
     */
    fun validateBatteryLevel(batteryLevel: Int): Boolean {
        return batteryLevel in 0..100
    }

    /**
     * Validates an altitude reading in meters above sea level.
     * - Must not be NaN/infinite
     * - Bounded to a sane range (Dead Sea shore to above Everest, with margin)
     */
    fun validateAltitude(altitude: Double): Boolean {
        if (altitude.isNaN() || altitude.isInfinite()) return false
        return altitude in -500.0..9000.0
    }
}