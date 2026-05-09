package com.lerchenflo.schneaggchatv3server.core.security.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
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

    private val log = LoggerFactory.getLogger(RateLimitFilter::class.java)

    private val staticPrefixes = listOf("/css/", "/js/", "/web_images/")
    private val staticSuffixes = listOf(".html", ".ico", ".png", ".js", ".css")

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

        try {
            if (request.servletPath.startsWith(properties.authPathPrefix)) {
                // Strict login bucket — checked first so denied logins don't drain
                // the shared auth bucket that refresh / password reset etc. also use.
                if (isLoginRequest(request)) {
                    val loginProbe = rateLimitService.tryConsume("rl:auth-login-ip:$ip", RateLimitTier.LOGIN)
                    if (!loginProbe.isConsumed) { deny(response, loginProbe.nanosToWaitForRefill); return }
                } else {
                    val authProbe = rateLimitService.tryConsume("rl:auth-ip:$ip", RateLimitTier.AUTH)
                    if (!authProbe.isConsumed) { deny(response, authProbe.nanosToWaitForRefill); return }
                }
            }

            val ipProbe = rateLimitService.tryConsume("rl:ip:$ip", RateLimitTier.IP)
            if (!ipProbe.isConsumed) { deny(response, ipProbe.nanosToWaitForRefill); return }

            if (userId != null) {
                val userProbe = rateLimitService.tryConsume("rl:user:$userId", RateLimitTier.USER)
                if (!userProbe.isConsumed) { deny(response, userProbe.nanosToWaitForRefill); return }
            }
        } catch (e: Exception) {
            log.warn("Rate limiter unavailable, failing open: ${e.message}")
        }

        filterChain.doFilter(request, response)
    }

    private fun isLoginRequest(request: HttpServletRequest): Boolean =
        request.method.equals("POST", ignoreCase = true) &&
            request.servletPath == "${properties.authPathPrefix}login"

    private fun isStaticPath(path: String): Boolean =
        staticPrefixes.any { path.startsWith(it) } || staticSuffixes.any { path.endsWith(it) }

    private fun deny(response: HttpServletResponse, nanosToWait: Long) {
        val retryAfter = TimeUnit.NANOSECONDS.toSeconds(nanosToWait) + 1
        response.status = 429
        response.setHeader("Retry-After", retryAfter.toString())
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write(
            objectMapper.writeValueAsString(
                mapOf("error" to "rate_limited", "retryAfterSeconds" to retryAfter)
            )
        )
    }
}
