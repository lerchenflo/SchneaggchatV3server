package com.lerchenflo.schneaggchatv3server.authentication

import com.lerchenflo.schneaggchatv3server.user.UserLookupService
import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.Locale.getDefault

//https://schneaggchatv3.eu/auth

@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
    private val emailService: EmailService,

    private val userLookupService: UserLookupService,
) {

    data class LoginRequest(
        @field:NotBlank(message = "Username must not be blank")
        @field:Size(max = 500, message = "Username too long")
        val username: String,
        @field:NotBlank(message = "Password must not be blank")
        @field:Size(max = 500, message = "Password too long")
        val password: String
    )

    data class RegisterRequest(
        @field:NotBlank(message = "Username must not be blank")
        @field:Size(min = 3, max = 25, message = "Username must be between 3 and 25 characters")
        val username: String,
        @field:NotBlank(message = "Password must not be blank")
        @field:Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
        @field:Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}\$",
            message = "Password must be at least 8 characters long and contain at least one digit, uppercase and lowercase character."
        )
        val password: String,
        @field:NotBlank(message = "Email must not be blank")
        @field:Size(max = 254, message = "Email too long")
        @field:Email(message = "Invalid email format.")
        val email: String,
        @field:NotBlank(message = "Birth date must not be blank")
        @field:Size(max = 10, message = "Birth date too long")
        val birthDate: String,
    )

    data class RefreshRequest(
        @field:NotBlank(message = "Refresh token must not be blank")
        @field:Size(max = 2000, message = "Refresh token too long")
        val refreshToken: String
    )

    //https://schneaggchat.eu/auth/register
    @PostMapping("/register", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun register(
        @RequestParam("username") username: String,
        @RequestParam("password") password: String,
        @RequestParam("email") email: String,
        @RequestParam("birthDate") birthDate: String,
        @RequestParam("profilepic") profilePic: MultipartFile
    ) {
        val user = authService.register(
            username = username.trim().lowercase(getDefault()),
            password = password,
            email = email.trim().lowercase(getDefault()),
            birthdate = birthDate,
            profilePic = profilePic
        )

        emailService.sendVerificationEmail(
            userId = user.id,
        )
    }


    @PostMapping("/login")
    fun login(
        @Valid @RequestBody loginRequest: LoginRequest
    ): AuthService.TokenPair {
        require(ValidationUtils.validateLoginInput(loginRequest.username)) { "Invalid username" }
        require(ValidationUtils.validateLoginInput(loginRequest.password)) { "Invalid password" }

        return authService.login(
            username = loginRequest.username.trim().lowercase(getDefault()),
            password = loginRequest.password,
        )
    }


    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody refreshRequest: RefreshRequest
    ): AuthService.TokenPair {


        try {
            require(ValidationUtils.validateToken(refreshRequest.refreshToken)) { "Invalid refresh token" }

            val tokenPair = authService.refresh(
                refreshRequest.refreshToken,
            )

            return tokenPair
        } catch (e: Exception) {
            throw e
        }
    }


    @GetMapping("/verify_email")
    fun verifyEmail(
        @RequestParam("token") token: String,
    ) : String {
        require(ValidationUtils.validateToken(token)) { "Invalid token" }

        return if (emailService.verifyEmailRequest(token)){
            //Email verified
            "Your email has been verified."
        }else {
            //Email not verified
            "Your email could not be verified"
        }
    }

    @PostMapping("/send_delete_email")
    fun sendDeleteAccEmail(
        @RequestParam("email") email: String,
    ){
        require(ValidationUtils.validateEmail(email)) { "Invalid email" }

        val user = userLookupService.findByEmail(email)
        if (user == null){
            AppLogger.warn("No user to delete found with email $email")
            return
        }

        AppLogger.info("Email delete request for $email")
        emailService.sendDelAccEmail(user.id, email)
    }


    @GetMapping("/delete_account")
    fun deleteAccount(
        @RequestParam("token") token: String,
    ) : String {
        require(ValidationUtils.validateToken(token)) { "Invalid token" }

        // Generate confirmation page
        val confirmToken = emailService.generateDelAccConfirmToken(token)
        
        return if (confirmToken != null) {
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Confirm Account Deletion</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 600px; margin: 50px auto; padding: 20px; }
                    .warning { color: #d32f2f; font-weight: bold; margin: 20px 0; }
                    .button { background-color: #d32f2f; color: white; padding: 10px 20px; border: none; cursor: pointer; }
                    .cancel { background-color: #666; color: white; padding: 10px 20px; border: none; cursor: pointer; margin-left: 10px; }
                </style>
            </head>
            <body>
                <h1>Confirm Account Deletion</h1>
                <div class="warning">
                    WARNING: This action cannot be undone. All your data will be permanently deleted.
                </div>
                <p>Are you sure you want to delete your account?</p>
                <form action="/auth/confirm_delete_account" method="post">
                    <input type="hidden" name="confirmToken" value="$confirmToken">
                    <button type="submit" class="button">Yes, Delete My Account</button>
                    <a href="/" class="cancel">Cancel</a>
                </form>
            </body>
            </html>
            """.trimIndent()
        } else {
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Invalid Link</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 600px; margin: 50px auto; padding: 20px; }
                    .error { color: #d32f2f; font-weight: bold; }
                </style>
            </head>
            <body>
                <h1>Invalid or Expired Link</h1>
                <div class="error">
                    The deletion link is invalid or has expired. Please request a new deletion email.
                </div>
                <a href="/">Return to Home</a>
            </body>
            </html>
            """.trimIndent()
        }
    }

    @PostMapping("/confirm_delete_account")
    fun confirmDeleteAccount(
        @RequestParam("confirmToken") confirmToken: String,
    ) : String {
        require(ValidationUtils.validateToken(confirmToken)) { "Invalid token" }

        return if (emailService.confirmDeleteAccount(confirmToken)){
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Account Deleted</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 600px; margin: 50px auto; padding: 20px; }
                    .success { color: #4caf50; font-weight: bold; }
                </style>
            </head>
            <body>
                <h1>Account Successfully Deleted</h1>
                <div class="success">
                    Your account has been permanently deleted. All your data has been removed.
                </div>
                <p>Thank you for using Schneaggchat.</p>
                <a href="/">Return to Home</a>
            </body>
            </html>
            """.trimIndent()
        } else {
            """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Deletion Failed</title>
                <style>
                    body { font-family: Arial, sans-serif; max-width: 600px; margin: 50px auto; padding: 20px; }
                    .error { color: #d32f2f; font-weight: bold; }
                </style>
            </head>
            <body>
                <h1>Account Deletion Failed</h1>
                <div class="error">
                    The confirmation token is invalid or has expired. Please request a new deletion email.
                </div>
                <a href="/">Return to Home</a>
            </body>
            </html>
            """.trimIndent()
        }
    }


    @PostMapping("/send_reset_email")
    fun sendResetEmail(
        @RequestParam("email") email: String,
    ){
        require(ValidationUtils.validateEmail(email)) { "Invalid email" }

        val user = userLookupService.findByEmail(email)
        if (user == null){
            AppLogger.warn("No user found with email $email for password reset")
            return
        }

        AppLogger.info("Password reset request for $email")
        emailService.sendPasswordResetEmail(user.id, email)
    }

    @GetMapping("/reset_password")
    fun resetPassword(
        @RequestParam("token") token: String,
    ) : ResponseEntity<Void> {
        require(ValidationUtils.validateToken(token)) { "Invalid token" }

        // Redirect to the static HTML page, passing the token as a query parameter
        return ResponseEntity.status(HttpStatus.FOUND)
            .header("Location", "/reset_password_form.html?token=$token")
            .build()
    }

    @PostMapping("/confirm_reset_password")
    fun confirmResetPassword(
        @RequestParam("token") token: String,
        @RequestParam("newPassword") newPassword: String,
    ) : String {
        require(ValidationUtils.validateToken(token)) { "Invalid token" }
        require(ValidationUtils.validatePassword(newPassword)) { "Password does not meet requirements" }
        
        return if (emailService.resetPassword(token, newPassword)) {
            "true"
        } else {
            "false"
        }
    }


}