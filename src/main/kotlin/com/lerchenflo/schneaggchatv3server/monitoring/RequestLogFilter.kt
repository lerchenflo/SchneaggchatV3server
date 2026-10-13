package com.lerchenflo.schneaggchatv3server.monitoring

import com.lerchenflo.schneaggchatv3server.core.security.ratelimit.ClientIpResolver
import com.lerchenflo.schneaggchatv3server.util.RequestLogContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

/**
 * Outermost filter (before Spring Security): opens the [RequestLogContext] every log line of the
 * request is stamped with, and records one `REQUEST` row for every response >= 400 - including
 * 401/429 answered by the security chain or the rate limiter, which never reach a controller.
 *
 * The request id is echoed in `X-Request-Id`, so a failing client call can be looked up in the
 * dashboard's trace view.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLogFilter(
    private val appLogService: AppLogService,
    private val clientIpResolver: ClientIpResolver,
) : OncePerRequestFilter() {

    private val idSegment = Regex("^([0-9a-fA-F]{24}|[0-9a-fA-F-]{36}|\\d+)$")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val context = RequestLogContext(
            requestId = UUID.randomUUID().toString().substring(0, 13),
            method = request.method,
            path = request.requestURI.take(300),
            clientIp = clientIpResolver.resolve(request),
        )
        RequestLogContext.open(context)
        response.setHeader("X-Request-Id", context.requestId)
        val start = System.nanoTime()
        var thrown: Throwable? = null

        try {
            filterChain.doFilter(request, response)
        } catch (t: Throwable) {
            thrown = t
            throw t
        } finally {
            try {
                val status = if (thrown != null) 500 else response.status
                if (status >= 400) {
                    appLogService.logFailedRequest(
                        context = context,
                        status = status,
                        endpoint = endpointOf(request, context.path),
                        durationMs = (System.nanoTime() - start) / 1_000_000,
                        thrown = thrown,
                    )
                }
            } finally {
                RequestLogContext.close()
            }
        }
    }

    /**
     * Route pattern Spring matched (`/groups/{id}`); for unmatched paths (bots probing for
     * `/wp-login.php`, static 404s) the path itself with id-like segments replaced by `{id}`.
     */
    private fun endpointOf(request: HttpServletRequest, path: String): String {
        val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
        if (pattern != null && pattern != "/**") return pattern
        return path.split('/').joinToString("/") { if (idSegment.matches(it)) "{id}" else it }.take(200)
    }
}
