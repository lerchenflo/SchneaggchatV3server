package com.lerchenflo.schneaggchatv3server.monitoring

import com.lerchenflo.schneaggchatv3server.core.security.ratelimit.ClientIpResolver
import com.lerchenflo.schneaggchatv3server.util.RequestLogContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.util.UUID

/**
 * Outermost filter (before Spring Security): opens the [RequestLogContext] every log line of the
 * request is stamped with, and records one `REQUEST` row for every response >= 400 - including
 * 401/429 answered by the security chain or the rate limiter, which never reach a controller.
 * Every request, failed or not, is also counted per endpoint ([RequestCountService]).
 *
 * The request id is echoed in `X-Request-Id`, so a failing client call can be looked up in the
 * dashboard's trace view.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLogFilter(
    private val appLogService: AppLogService,
    private val requestCountService: RequestCountService,
    private val clientIpResolver: ClientIpResolver,
    // Resolved on first use: filters are created before the MVC infrastructure
    @Qualifier("requestMappingHandlerMapping")
    private val handlerMapping: ObjectProvider<RequestMappingHandlerMapping>,
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
                val pattern = routePattern(request) ?: if (status >= 400) lookUpRoutePattern(request) else null
                // A failed unmatched path counts as one shared endpoint: bot probes would otherwise
                // add a counter row per made-up path. Unmatched paths that worked are the website's
                // static files, a fixed set.
                requestCountService.record(
                    method = request.method,
                    endpoint = pattern ?: if (status >= 400) RequestCountService.UNMATCHED_ENDPOINT else normalizedPath(context.path),
                    status = status,
                )
                if (status >= 400) {
                    appLogService.logFailedRequest(
                        context = context,
                        status = status,
                        endpoint = pattern ?: normalizedPath(context.path),
                        durationMs = (System.nanoTime() - start) / 1_000_000,
                        thrown = thrown,
                    )
                }
            } finally {
                RequestLogContext.close()
            }
        }
    }

    /** The route pattern Spring matched (`/groups/{id}`), null for unmatched paths and static files (the catch-all pattern). */
    private fun routePattern(request: HttpServletRequest): String? =
        (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String)?.takeIf { it != "/**" }

    /**
     * Route pattern of a request the security chain or the rate limiter answered (401/429): it
     * never reached the DispatcherServlet, so nothing matched it yet. Asks the controller mapping
     * directly, which sets [HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE] on a match. Null when
     * no route fits (bot probes) or the path fits but the method or media type does not.
     */
    private fun lookUpRoutePattern(request: HttpServletRequest): String? = try {
        handlerMapping.getIfAvailable()?.getHandler(request)
        routePattern(request)
    } catch (e: Exception) {
        null
    }

    /** For unmatched paths (bots probing for `/wp-login.php`, static files): the path with id-like segments replaced by `{id}`. */
    private fun normalizedPath(path: String): String =
        path.split('/').joinToString("/") { if (idSegment.matches(it)) "{id}" else it }.take(200)
}
