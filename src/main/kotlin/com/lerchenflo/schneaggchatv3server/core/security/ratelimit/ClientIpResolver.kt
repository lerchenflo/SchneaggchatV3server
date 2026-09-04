package com.lerchenflo.schneaggchatv3server.core.security.ratelimit

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.stereotype.Component

/**
 * Resolves the address a request is rate limited by.
 *
 * `X-Real-IP` / `X-Forwarded-For` are believed only when the request actually arrives from one of
 * [RateLimitProperties.trustedProxies] - otherwise every IP-keyed limit (including the login
 * throttle) would be bypassable by sending a made-up header value with each request.
 */
@Component
class ClientIpResolver(
    properties: RateLimitProperties,
) {
    private val trustedProxyMatchers = properties.trustedProxies.map { IpAddressMatcher(it) }

    fun resolve(request: HttpServletRequest): String {
        if (!isFromTrustedProxy(request)) return request.remoteAddr

        val realIp = request.getHeader("X-Real-IP")
        if (!realIp.isNullOrBlank()) return realIp.trim()

        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) return forwarded.split(",")[0].trim()

        return request.remoteAddr
    }

    private fun isFromTrustedProxy(request: HttpServletRequest): Boolean =
        trustedProxyMatchers.any { it.matches(request) }
}
