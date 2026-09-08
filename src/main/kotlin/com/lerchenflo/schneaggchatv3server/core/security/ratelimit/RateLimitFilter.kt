package com.lerchenflo.schneaggchatv3server.core.security.ratelimit

import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.util.Json
import io.github.bucket4j.ConsumptionProbe
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.TimeUnit

@Component
class RateLimitFilter(
    private val rateLimitService: RateLimitService,
    private val clientIpResolver: ClientIpResolver,
    private val properties: RateLimitProperties
) : OncePerRequestFilter() {

    private val staticPrefixes = listOf("/css/", "/js/", "/web_images/", "/i18n/")
    private val staticSuffixes = listOf(".html", ".ico", ".png", ".js", ".css", ".xml", ".webp")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (!properties.enabled || isStaticPath(request.servletPath)) {
            filterChain.doFilter(request, response)
            return
        }

        val ip = clientIpResolver.resolve(request)
        val userId = SecurityContextHolder.getContext().authentication?.principal as? String
        val requestDescription = "${request.method} ${request.servletPath} ip=$ip user=${userId ?: "-"}"

        if (request.servletPath.startsWith(properties.authPathPrefix)) {
            try {
                val probe = rateLimitService.tryConsume("rl:auth-ip:$ip", RateLimitTier.AUTH)
                logConsumption(RateLimitTier.AUTH, "rl:auth-ip:$ip", probe, requestDescription)
                if (!probe.isConsumed) {
                    denyLimitReached(response, RateLimitTier.AUTH, probe, requestDescription)
                    return
                }
            } catch (e: Exception) {
                AppLogger.warn("RATELIMIT unavailable on auth path, failing closed ($requestDescription): ${e.message}")
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE)
                return
            }
        }

        try {
            val ipProbe = rateLimitService.tryConsume("rl:ip:$ip", RateLimitTier.IP)
            logConsumption(RateLimitTier.IP, "rl:ip:$ip", ipProbe, requestDescription)
            if (!ipProbe.isConsumed) {
                denyLimitReached(response, RateLimitTier.IP, ipProbe, requestDescription)
                return
            }

            if (userId != null) {
                val userProbe = rateLimitService.tryConsume("rl:user:$userId", RateLimitTier.USER)
                logConsumption(RateLimitTier.USER, "rl:user:$userId", userProbe, requestDescription)
                if (!userProbe.isConsumed) {
                    denyLimitReached(response, RateLimitTier.USER, userProbe, requestDescription)
                    return
                }
            }
        } catch (e: Exception) {
            AppLogger.warn("RATELIMIT unavailable, failing open ($requestDescription): ${e.message}")
        }

        filterChain.doFilter(request, response)
    }

    private fun isStaticPath(path: String): Boolean =
        staticPrefixes.any { path.startsWith(it) } || staticSuffixes.any { path.endsWith(it) }

    private fun logConsumption(
        tier: RateLimitTier,
        key: String,
        probe: ConsumptionProbe,
        requestDescription: String
    ) {
        if (!properties.debugLogging) return
        val capacity = properties.tierConfig(tier).capacity
        AppLogger.debug(
            "RATELIMIT ${tier.name} key=$key ${probe.remainingTokens}/$capacity left" +
                    "${if (probe.isConsumed) "" else " DENIED"} | $requestDescription"
        )
    }

    private fun denyLimitReached(
        response: HttpServletResponse,
        tier: RateLimitTier,
        probe: ConsumptionProbe,
        requestDescription: String
    ) {
        val retryAfter = TimeUnit.NANOSECONDS.toSeconds(probe.nanosToWaitForRefill) + 1
        val limit = properties.tierConfig(tier)
        AppLogger.warn(
            "RATELIMIT ${tier.name} exceeded (more than ${limit.capacity} requests per " +
                    "${limit.refillPeriod}), 429 for ${retryAfter}s | $requestDescription"
        )

        response.status = 429
        response.setHeader("Retry-After", retryAfter.toString())
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write(
            Json.mapper.writeValueAsString(
                mapOf("error" to "rate_limited", "retryAfterSeconds" to retryAfter)
            )
        )
    }
}
