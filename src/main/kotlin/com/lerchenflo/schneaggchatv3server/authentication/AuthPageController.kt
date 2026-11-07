package com.lerchenflo.schneaggchatv3server.authentication

import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * The pages behind the links in our emails, rendered from `templates/auth/`. Not part of
 * [AuthController]: in a `@RestController` a returned view name would be sent as plain text.
 */
@Controller
@RequestMapping("/auth")
class AuthPageController(
    private val emailService: EmailService,
) {

    @GetMapping("/verify_email")
    fun verifyEmail(
        @RequestParam("token") token: String,
    ): String {
        require(ValidationUtils.validateToken(token)) { "Invalid token" }

        return if (emailService.verifyEmailRequest(token)) "auth/verify_email_success" else "auth/verify_email_failed"
    }

    @GetMapping("/delete_account")
    fun deleteAccount(
        @RequestParam("token") token: String,
        model: Model,
    ): String {
        require(ValidationUtils.validateToken(token)) { "Invalid token" }

        val confirmToken = emailService.generateDelAccConfirmToken(token) ?: return "auth/delete_account_invalid"

        model.addAttribute("confirmToken", confirmToken)
        return "auth/delete_account_confirm"
    }

    @PostMapping("/confirm_delete_account")
    fun confirmDeleteAccount(
        @RequestParam("confirmToken") confirmToken: String,
    ): String {
        require(ValidationUtils.validateToken(confirmToken)) { "Invalid token" }

        return if (emailService.confirmDeleteAccount(confirmToken)) "auth/delete_account_success" else "auth/delete_account_failed"
    }
}
