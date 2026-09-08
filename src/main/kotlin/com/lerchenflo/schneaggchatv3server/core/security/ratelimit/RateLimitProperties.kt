package com.lerchenflo.schneaggchatv3server.core.security.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,

    // Logs every bucket consumption with the tokens left, not just the requests that get a 429.
    val debugLogging: Boolean = false,
    val ip: TierConfig = TierConfig(100L, Duration.ofMinutes(1)),
    val user: TierConfig = TierConfig(300L, Duration.ofMinutes(1)),
    val auth: TierConfig = TierConfig(10L, Duration.ofMinutes(1)),
    // Per-account login throttle. Consumed only by failed logins, so a legitimate user never sees it
    // unless their account is actually under attack. Unlike the IP tiers this one cannot be evaded
    // by rotating source addresses - the account being guessed is the key.
    val authUser: TierConfig = TierConfig(10L, Duration.ofMinutes(15)),
    val authPathPrefix: String = "/auth/",

    // Source addresses whose X-Real-IP / X-Forwarded-For headers may be believed. Anything else is
    // treated as a direct client and rate limited by its real socket address, so a client cannot
    // hand itself a fresh identity per request by making a header up.
    //
    // DEPLOYMENT NOTE (security audits, see SECURITY_AUDIT.md H-1): production sits behind a reverse
    // proxy that terminates the client connection and sets X-Real-IP / X-Forwarded-For from the real
    // client socket, replacing anything the client sent. The proxy reaches the app container over the
    // Docker network, so its requests arrive from the private ranges below - that is why they are
    // trusted. Header values reaching this code are therefore proxy-set, not client-supplied; this is
    // not a spoofing hole.
    val trustedProxies: List<String> = listOf(
        "127.0.0.1/32",
        "::1/128",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
    ),
) {
    fun tierConfig(tier: RateLimitTier): TierConfig = when (tier) {
        RateLimitTier.IP -> ip
        RateLimitTier.USER -> user
        RateLimitTier.AUTH -> auth
        RateLimitTier.AUTH_USER -> authUser
    }

    data class TierConfig(
        val capacity: Long = 100L,
        val refillPeriod: Duration = Duration.ofMinutes(1)
    )
}
