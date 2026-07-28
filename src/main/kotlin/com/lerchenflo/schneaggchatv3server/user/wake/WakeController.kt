package com.lerchenflo.schneaggchatv3server.user.wake

import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

const val MAX_REASON_LENGTH = 200

@RestController
@RequestMapping("/wake")
class WakeController(
    private val wakeService: WakeService
) {

    data class WakeRequest(
        @field:NotBlank(message = "Target must not be blank")
        val targetId: String,

        val isGroup: Boolean = false,

        @field:Size(max = MAX_REASON_LENGTH, message = "Reason too long")
        val reason: String = "",
    )

    /**
     * Wake a friend or a group: plays an alarm on every consenting Android device of the target.
     * Whoever is not reachable is reported back through [WakeResponse.outcome] rather than an
     * error, so the sender can tell "nobody allows this" apart from "it went through".
     */
    @PostMapping("/send")
    fun sendWake(@Valid @RequestBody request: WakeRequest): WakeResponse {
        val senderId = requireAuth()
        require(ValidationUtils.validateObjectId(request.targetId)) { "Invalid target ID" }

        return wakeService.sendWake(
            senderId = senderId,
            targetId = ObjectId(request.targetId),
            isGroup = request.isGroup,
            reason = request.reason.trim(),
        )
    }
}
