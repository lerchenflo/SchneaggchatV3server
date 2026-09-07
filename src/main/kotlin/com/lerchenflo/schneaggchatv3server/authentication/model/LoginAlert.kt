@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.authentication.model

import com.lerchenflo.schneaggchatv3server.authentication.AuthController
import com.lerchenflo.schneaggchatv3server.user.usermodel.User
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Everything the server knows about one successful `/auth/login`, handed to
 * `EmailService.sendLoginAlertEmail` so the account owner can judge whether it was them.
 * All string fields except [user] are client-supplied and get sanitised before they reach a mail.
 */
data class LoginAlert(
    val user: User,
    val deviceName: String,
    val deviceType: AuthController.DEVICETYPE,
    /** True when no unexpired session row existed for this device name + type before the login. */
    val newDevice: Boolean,
    /** Resolved client address (see `ClientIpResolver`), null only from internal callers. */
    val ip: String?,
    /** Raw `User-Agent` header - the browser/OS for WEB logins, the Ktor default for the app. */
    val userAgent: String?,
    /** Raw `Accept-Language` header, if the client sent one. */
    val acceptLanguage: String?,
    val loginTime: Instant,
    /** Timestamp of this account's previous successful login (the last `USER_LOGIN` log), null for the first one. Drives the mail throttle. */
    val previousLoginAt: Instant?,
)
