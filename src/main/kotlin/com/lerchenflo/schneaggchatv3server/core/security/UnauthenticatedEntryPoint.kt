package com.lerchenflo.schneaggchatv3server.core.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpStatus
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.util.ServletRequestPathUtils

/**
 * Answer for a request without a valid token on a path that is not public.
 *
 * - `/index.html` redirects (301) to `/`, the only address of the start page.
 * - A file-like path (`/impressum.html`) that no controller handles answers 404, so a missing
 *   website page is "not found" for browsers and search engines instead of "unauthorized".
 * - Everything else answers 401 as before. API routes, `/ws` and actuator have no file extension,
 *   and clients refresh their token on that 401, so it must stay.
 */
@Component
class UnauthenticatedEntryPoint(
    @param:Lazy @param:Qualifier("requestMappingHandlerMapping")
    private val handlerMapping: RequestMappingHandlerMapping,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val path = request.requestURI.removePrefix(request.contextPath)
        when {
            path == "/index.html" -> {
                response.status = HttpStatus.MOVED_PERMANENTLY.value()
                response.setHeader("Location", "${request.contextPath}/")
            }
            isFilePath(path) && !hasController(request) -> response.sendError(HttpStatus.NOT_FOUND.value())
            else -> response.status = HttpStatus.UNAUTHORIZED.value()
        }
    }

    private fun isFilePath(path: String): Boolean = '.' in path.substringAfterLast('/')

    /** Whether a controller maps the path, also with another HTTP method (that is still a real route). */
    private fun hasController(request: HttpServletRequest): Boolean {
        val parsedHere = !ServletRequestPathUtils.hasParsedRequestPath(request)
        if (parsedHere) ServletRequestPathUtils.parseAndCache(request)
        return try {
            handlerMapping.getHandler(request) != null
        } catch (e: Exception) {
            true // The path matches, only method or media type do not: keep the 401
        } finally {
            if (parsedHere) ServletRequestPathUtils.clearParsedRequestPath(request)
        }
    }
}
