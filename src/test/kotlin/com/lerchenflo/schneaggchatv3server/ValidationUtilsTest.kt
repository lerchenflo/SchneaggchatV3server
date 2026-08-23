package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.util.ValidationUtils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.springframework.mock.web.MockMultipartFile
import java.time.LocalDate

@DisplayName("ValidationUtils Tests")
class ValidationUtilsTest {

    // ===== EMAIL VALIDATION TESTS =====

    @Test
    @DisplayName("Valid email should pass validation")
    fun `validateEmail should return true for valid emails`() {
        assertTrue(ValidationUtils.validateEmail("user@example.com"))
        assertTrue(ValidationUtils.validateEmail("test.user@domain.co.uk"))
        assertTrue(ValidationUtils.validateEmail("user+tag@example.com"))
        assertTrue(ValidationUtils.validateEmail("user_name@example.com"))
        assertTrue(ValidationUtils.validateEmail("123@example.com"))
    }

    @Test
    @DisplayName("Invalid email should fail validation")
    fun `validateEmail should return false for invalid emails`() {
        assertFalse(ValidationUtils.validateEmail(""))
        assertFalse(ValidationUtils.validateEmail("   "))
        assertFalse(ValidationUtils.validateEmail("notanemail"))
        assertFalse(ValidationUtils.validateEmail("@example.com"))
        assertFalse(ValidationUtils.validateEmail("user@"))
        assertFalse(ValidationUtils.validateEmail("user@.com"))
        assertFalse(ValidationUtils.validateEmail("user name@example.com"))
    }

    @Test
    @DisplayName("Email exceeding max length should fail")
    fun `validateEmail should return false for emails longer than 254 characters`() {
        val longEmail = "a".repeat(250) + "@example.com"
        assertFalse(ValidationUtils.validateEmail(longEmail))
    }


    // ===== BIRTHDATE VALIDATION TESTS =====

    @Test
    @DisplayName("Valid birthdate should pass validation")
    fun `validateBirthdate should return true for valid birthdates`() {
        assertTrue(ValidationUtils.validateBirthdate("2000-01-01"))
        assertTrue(ValidationUtils.validateBirthdate("1990-12-31"))
        assertTrue(ValidationUtils.validateBirthdate("2004-08-13"))
        assertTrue(ValidationUtils.validateBirthdate("1980-06-15"))
    }

    @Test
    @DisplayName("Invalid format should fail validation")
    fun `validateBirthdate should return false for invalid formats`() {
        assertFalse(ValidationUtils.validateBirthdate(""))           // Empty
        assertFalse(ValidationUtils.validateBirthdate("   "))        // Blank
        assertFalse(ValidationUtils.validateBirthdate("13-08-2004")) // DD-MM-YYYY
        assertFalse(ValidationUtils.validateBirthdate("08/13/2004")) // MM/DD/YYYY
        assertFalse(ValidationUtils.validateBirthdate("2004.08.13")) // Dots as separator
        assertFalse(ValidationUtils.validateBirthdate("20040813"))   // No separators
        assertFalse(ValidationUtils.validateBirthdate("2004-8-13"))  // Missing leading zero
    }

    @Test
    @DisplayName("Invalid calendar date should fail validation")
    fun `validateBirthdate should return false for invalid calendar dates`() {
        assertFalse(ValidationUtils.validateBirthdate("2004-02-30")) // Feb 30 doesn't exist
        assertFalse(ValidationUtils.validateBirthdate("2004-02-31")) // Feb 31 doesn't exist
        assertFalse(ValidationUtils.validateBirthdate("2004-13-01")) // Month 13 doesn't exist
        assertFalse(ValidationUtils.validateBirthdate("2004-00-01")) // Month 0 doesn't exist
        assertFalse(ValidationUtils.validateBirthdate("2004-01-00")) // Day 0 doesn't exist
        assertFalse(ValidationUtils.validateBirthdate("2004-01-32")) // Day 32 doesn't exist
    }

    @Test
    @DisplayName("Future birthdate should fail validation")
    fun `validateBirthdate should return false for future dates`() {
        val tomorrow = LocalDate.now().plusDays(1).toString()
        val nextYear = LocalDate.now().plusYears(1).toString()

        assertFalse(ValidationUtils.validateBirthdate(tomorrow))
        assertFalse(ValidationUtils.validateBirthdate(nextYear))
    }

    @Test
    @DisplayName("User exactly 8 years old should pass validation")
    fun `validateBirthdate should return true if user is exactly 8`() {
        val exactlyEight = LocalDate.now().minusYears(8).toString()
        assertTrue(ValidationUtils.validateBirthdate(exactlyEight))
    }

    @Test
    @DisplayName("Unrealistically old birthdate should fail validation")
    fun `validateBirthdate should return false if user is older than 120 years`() {
        val tooOld = LocalDate.now().minusYears(121).toString()
        assertFalse(ValidationUtils.validateBirthdate(tooOld))
    }

    @Test
    @DisplayName("Exactly 120 years old should pass validation")
    fun `validateBirthdate should return true if user is exactly 120 years old`() {
        val exactlyMaxAge = LocalDate.now().minusYears(120).toString()
        assertTrue(ValidationUtils.validateBirthdate(exactlyMaxAge))
    }

    // ===== PASSWORD VALIDATION TESTS =====

    @Test
    @DisplayName("Valid password should pass validation")
    fun `validatePassword should return true for valid passwords`() {
        assertTrue(ValidationUtils.validatePassword("Password123!"))
        assertTrue(ValidationUtils.validatePassword("Str0ng!Pass"))
        assertTrue(ValidationUtils.validatePassword("MyP@ssw0rd"))
        assertTrue(ValidationUtils.validatePassword("Abcdef1!"))
    }

    @Test
    @DisplayName("Password missing requirements should fail")
    fun `validatePassword should return false for weak passwords`() {
        assertFalse(ValidationUtils.validatePassword("short1!")) // Too short
        assertFalse(ValidationUtils.validatePassword("password123!")) // No uppercase
        assertFalse(ValidationUtils.validatePassword("PASSWORD123!")) // No lowercase
        assertFalse(ValidationUtils.validatePassword("Password!")) // No digit
        assertFalse(ValidationUtils.validatePassword("Password123")) // No special char
        assertFalse(ValidationUtils.validatePassword("")) // Empty
    }

    @Test
    @DisplayName("Password exceeding max length should fail")
    fun `validatePassword should return false for passwords longer than 128 characters`() {
        val longPassword = "A1!" + "a".repeat(130)
        assertFalse(ValidationUtils.validatePassword(longPassword))
    }

    // ===== USERNAME VALIDATION TESTS =====

    @Test
    @DisplayName("Valid username should pass validation")
    fun `validateUsername should return true for valid usernames`() {
        assertTrue(ValidationUtils.validateUsername("john_doe"))
        assertTrue(ValidationUtils.validateUsername("user123"))
        assertTrue(ValidationUtils.validateUsername("test-user"))
        assertTrue(ValidationUtils.validateUsername("abcd"))
        assertTrue(ValidationUtils.validateUsername("User_Name-123"))
    }

    @Test
    @DisplayName("Invalid username should fail validation")
    fun `validateUsername should return false for invalid usernames`() {
        assertFalse(ValidationUtils.validateUsername("")) // Empty
        assertFalse(ValidationUtils.validateUsername("ab")) // Too short
        assertFalse(ValidationUtils.validateUsername("a".repeat(26))) // Too long
        assertFalse(ValidationUtils.validateUsername("_username")) // Starts with underscore
        assertFalse(ValidationUtils.validateUsername("-username")) // Starts with hyphen
        assertFalse(ValidationUtils.validateUsername("username_")) // Ends with underscore
        assertFalse(ValidationUtils.validateUsername("username-")) // Ends with hyphen
        assertFalse(ValidationUtils.validateUsername("user@name")) // Contains special char
    }

    // ===== PICTURE VALIDATION TESTS =====

    @Test
    @DisplayName("Valid image should pass validation")
    fun `validatePicture should return true for valid images`() {
        val jpegFile = MockMultipartFile("file", "image.jpg", "image/jpeg", ByteArray(1024))
        val pngFile = MockMultipartFile("file", "image.png", "image/png", ByteArray(1024))
        val gifFile = MockMultipartFile("file", "image.gif", "image/gif", ByteArray(1024))
        val webpFile = MockMultipartFile("file", "image.webp", "image/webp", ByteArray(1024))

        assertTrue(ValidationUtils.validatePicture(jpegFile))
        assertTrue(ValidationUtils.validatePicture(pngFile))
        assertTrue(ValidationUtils.validatePicture(gifFile))
        assertTrue(ValidationUtils.validatePicture(webpFile))
    }

    @Test
    @DisplayName("Empty file should fail validation")
    fun `validatePicture should return false for empty files`() {
        val emptyFile = MockMultipartFile("file", "image.jpg", "image/jpeg", ByteArray(0))
        assertFalse(ValidationUtils.validatePicture(emptyFile))
    }

    @Test
    @DisplayName("File exceeding size limit should fail")
    fun `validatePicture should return false for files larger than 5MB`() {
        val largeFile = MockMultipartFile("file", "large.jpg", "image/jpeg", ByteArray(6 * 1024 * 1024))
        assertFalse(ValidationUtils.validatePicture(largeFile))
    }

    @Test
    @DisplayName("Invalid content type should fail")
    fun `validatePicture should return false for invalid content types`() {
        val pdfFile = MockMultipartFile("file", "document.pdf", "application/pdf", ByteArray(1024))
        val textFile = MockMultipartFile("file", "file.txt", "text/plain", ByteArray(1024))

        assertFalse(ValidationUtils.validatePicture(pdfFile))
        assertFalse(ValidationUtils.validatePicture(textFile))
    }

    @Test
    @DisplayName("Invalid file extension should fail")
    fun `validatePicture should return false for invalid extensions`() {
        val invalidExt = MockMultipartFile("file", "image.bmp", "image/jpeg", ByteArray(1024))
        assertFalse(ValidationUtils.validatePicture(invalidExt))
    }

    @Test
    @DisplayName("File with no extension should fail")
    fun `validatePicture should return false for files without extension`() {
        val noExt = MockMultipartFile("file", "image", "image/jpeg", ByteArray(1024))
        assertFalse(ValidationUtils.validatePicture(noExt))
    }

    @Test
    @DisplayName("File with null content type should fail")
    fun `validatePicture should return false for null content type`() {
        val nullContentType = MockMultipartFile("file", "image.jpg", null, ByteArray(1024))
        assertFalse(ValidationUtils.validatePicture(nullContentType))
    }

    // ===== AUDIO VALIDATION TESTS =====

    @Test
    @DisplayName("Valid audio should pass validation")
    fun `validateAudio should return true for valid audio files`() {
        val m4aFile = MockMultipartFile("file", "message_audio.m4a", "audio/mp4", ByteArray(1024))
        assertTrue(ValidationUtils.validateAudio(m4aFile))
    }

    @Test
    @DisplayName("Empty audio file should fail validation")
    fun `validateAudio should return false for empty files`() {
        val emptyFile = MockMultipartFile("file", "audio.m4a", "audio/mp4", ByteArray(0))
        assertFalse(ValidationUtils.validateAudio(emptyFile))
    }

    @Test
    @DisplayName("Audio file exceeding size limit should fail")
    fun `validateAudio should return false for files larger than 2MB`() {
        val largeFile = MockMultipartFile("file", "audio.m4a", "audio/mp4", ByteArray(3 * 1024 * 1024))
        assertFalse(ValidationUtils.validateAudio(largeFile))
    }

    @Test
    @DisplayName("Invalid audio content type should fail")
    fun `validateAudio should return false for invalid content types`() {
        val textFile = MockMultipartFile("file", "audio.m4a", "text/plain", ByteArray(1024))
        assertFalse(ValidationUtils.validateAudio(textFile))
    }

    @Test
    @DisplayName("Invalid audio file extension should fail")
    fun `validateAudio should return false for invalid extensions`() {
        val invalidExt = MockMultipartFile("file", "audio.mp3", "audio/mp4", ByteArray(1024))
        assertFalse(ValidationUtils.validateAudio(invalidExt))
    }

    // ===== POLL TITLE VALIDATION TESTS =====

    @Test
    @DisplayName("Valid poll title should pass validation")
    fun `validatePollTitle should return true for valid titles`() {
        assertTrue(ValidationUtils.validatePollTitle("What should we eat?"))
    }

    @Test
    @DisplayName("Blank poll title should fail validation")
    fun `validatePollTitle should return false for blank titles`() {
        assertFalse(ValidationUtils.validatePollTitle(""))
        assertFalse(ValidationUtils.validatePollTitle("   "))
    }

    @Test
    @DisplayName("Poll title exceeding max length should fail")
    fun `validatePollTitle should return false for titles longer than 200 characters`() {
        assertFalse(ValidationUtils.validatePollTitle("a".repeat(201)))
    }

    // ===== POLL DESCRIPTION VALIDATION TESTS =====

    @Test
    @DisplayName("Null poll description should pass validation")
    fun `validatePollDescription should return true for null description`() {
        assertTrue(ValidationUtils.validatePollDescription(null))
    }

    @Test
    @DisplayName("Valid poll description should pass validation")
    fun `validatePollDescription should return true for valid description`() {
        assertTrue(ValidationUtils.validatePollDescription("Pick your favorite option"))
    }

    @Test
    @DisplayName("Poll description exceeding max length should fail")
    fun `validatePollDescription should return false for descriptions longer than 500 characters`() {
        assertFalse(ValidationUtils.validatePollDescription("a".repeat(501)))
    }
}