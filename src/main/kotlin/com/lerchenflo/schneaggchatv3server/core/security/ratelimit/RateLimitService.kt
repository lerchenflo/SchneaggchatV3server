package com.lerchenflo.schneaggchatv3server.core.security.ratelimit

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.ConsumptionProbe
import io.github.bucket4j.distributed.proxy.ProxyManager
import org.springframework.stereotype.Service

enum class RateLimitTier { IP, USER, AUTH, AUTH_USER }

@Service
class RateLimitService(
    private val proxyManager: ProxyManager<String>,
    private val properties: RateLimitProperties
) {
    fun tryConsume(key: String, tier: RateLimitTier): ConsumptionProbe =
        proxyManager.builder()
            .build(key) { buildConfig(tier) }
            .tryConsumeAndReturnRemaining(1)

    /** Reads a bucket without consuming from it - for limits that are only charged on failure. */
    fun availableTokens(key: String, tier: RateLimitTier): Long =
        proxyManager.builder()
            .build(key) { buildConfig(tier) }
            .availableTokens

    private fun buildConfig(tier: RateLimitTier): BucketConfiguration {
        val tc = when (tier) {
            RateLimitTier.IP -> properties.ip
            RateLimitTier.USER -> properties.user
            RateLimitTier.AUTH -> properties.auth
            RateLimitTier.AUTH_USER -> properties.authUser
        }
        return BucketConfiguration.builder()
            .addLimit(
                Bandwidth.builder()
                    .capacity(tc.capacity)
                    .refillGreedy(tc.capacity, tc.refillPeriod)
                    .build()
            )
            .build()
    }
}
