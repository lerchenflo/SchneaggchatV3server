package com.lerchenflo.schneaggchatv3server.core.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(
    private val jwtService: JwtService,

    @Value("\${prometheus.key}") private val prometheusKey: String

): OncePerRequestFilter() {


    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {

        //Filter out metrics endpoint
        if (request.requestURI.startsWith("/actuator")) {
            val providedKey = request.getHeader("Authorization")?.removePrefix("Bearer ")
            if (providedKey == null || providedKey  != prometheusKey) {
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                return
            }
        }


        //Filter all other requests
        // Authorization: Bearer <Token>
        val authHeader = request.getHeader("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            if (jwtService.validateAccessToken(authHeader)) {
                val userId = jwtService.getUserIdFromToken(authHeader)

                val auth = UsernamePasswordAuthenticationToken(userId, null, emptyList())
                SecurityContextHolder.getContext().authentication = auth

            }
        }

        filterChain.doFilter(request, response)
    }


}