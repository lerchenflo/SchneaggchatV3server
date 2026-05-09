package com.lerchenflo.schneaggchatv3server.core.security.ratelimit

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.stereotype.Component

@Component
class ClientIpResolver(
    properties: RateLimitProperties,
) {
    private val log = LoggerFactory.getLogger(ClientIpResolver::class.java)

    private val trustedProxyMatchers: List<IpAddressMatcher> =
        properties.trustedProxies.mapNotNull { cidr ->
            try {
                IpAddressMatcher(cidr)
            } catch (e: IllegalArgumentException) {
                log.warn("Ignoring invalid CIDR in rate-limit.trusted-proxies: '$cidr' — ${e.message}")
                null
            }
        }

    fun resolve(request: HttpServletRequest): String {
        val remoteAddr = request.remoteAddr ?: return "unknown"

        // Only honour forwarding headers when the immediate peer is a configured proxy —
        // otherwise any client could spoof X-Real-IP / X-Forwarded-For to dodge per-IP limits.
        if (trustedProxyMatchers.none { it.matches(remoteAddr) }) {
            return remoteAddr
        }

        request.getHeader("X-Real-IP")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        request.getHeader("X-Forwarded-For")
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return remoteAddr
    }
}
