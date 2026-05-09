package com.lerchenflo.schneaggchatv3server.core.security.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    val ip: TierConfig = TierConfig(100L, Duration.ofMinutes(1)),
    val user: TierConfig = TierConfig(300L, Duration.ofMinutes(1)),
    val auth: TierConfig = TierConfig(10L, Duration.ofMinutes(1)),
    val login: TierConfig = TierConfig(5L, Duration.ofMinutes(1)),
    val authPathPrefix: String = "/auth/",
    // CIDRs of proxies whose X-Real-IP / X-Forwarded-For headers we trust.
    // Empty = trust no headers (use raw remote address). Required to prevent
    // clients from spoofing their IP to bypass per-IP rate limits.
    val trustedProxies: List<String> = emptyList(),
) {
    data class TierConfig(
        val capacity: Long = 100L,
        val refillPeriod: Duration = Duration.ofMinutes(1)
    )
}
