package com.lerchenflo.schneaggchatv3server.core.security.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    val ip: TierConfig = TierConfig(100L, Duration.ofMinutes(1)),
    val user: TierConfig = TierConfig(300L, Duration.ofMinutes(1)),
    val auth: TierConfig = TierConfig(10L, Duration.ofMinutes(1)),
    val authPathPrefix: String = "/auth/"
) {
    data class TierConfig(
        val capacity: Long = 100L,
        val refillPeriod: Duration = Duration.ofMinutes(1)
    )
}
