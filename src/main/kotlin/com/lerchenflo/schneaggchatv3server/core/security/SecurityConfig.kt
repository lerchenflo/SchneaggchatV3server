package com.lerchenflo.schneaggchatv3server.core.security

import com.lerchenflo.schneaggchatv3server.core.security.ratelimit.RateLimitFilter
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    private val rateLimitFilter: RateLimitFilter
) {

    //Create empty default user for ignoring error message
    @Bean
    fun userDetailsService(): UserDetailsService =
        InMemoryUserDetailsManager()

    @Bean
    fun filterChain(httpSecurity: HttpSecurity): SecurityFilterChain {
        return httpSecurity
            .csrf { csrf -> csrf.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    //Allow auth endpoint (Login Register token refresh)
                    .requestMatchers("/auth/**")
                    .permitAll()
                    //Public endpoint for common stuff (Ping etc)
                    .requestMatchers("/public/test")
                    .permitAll()



                    /* Website (Auto served from static resources) */
                    //Allow index
                    .requestMatchers("/")
                    .permitAll()

                    //Privacy policy
                    .requestMatchers("/privacypolicy.html")
                    .permitAll()

                    //Account löschen
                    .requestMatchers("/delete_account.html")
                    .permitAll()

                    //Passwort zurücksetzen
                    .requestMatchers("/reset_password.html")
                    .permitAll()

                    .requestMatchers("/reset_password_form.html")
                    .permitAll()

                    .requestMatchers("/stats.html")
                    .permitAll()

                    .requestMatchers("/donations.html")
                    .permitAll()

                    //FAQ
                    .requestMatchers("/faq.html")
                    .permitAll()

                    //Admin panel shell - the HTML carries no data, everything is fetched afterwards
                    //via /chefdev/api/** which stays authenticated + role-gated. A browser navigation
                    //cannot carry an Authorization header, so the shell itself must be public.
                    .requestMatchers("/chefdev.html")
                    .permitAll()

                    //Public donation totals for the donations page
                    .requestMatchers("/public/donations")
                    .permitAll()

                    //Public FAQ entries for the FAQ page
                    .requestMatchers("/public/faq")
                    .permitAll()

                    //Favicon
                    .requestMatchers("/favicon.ico")
                    .permitAll()
                    //Style
                    .requestMatchers("/css/**")
                    .permitAll()
                    //Javascript
                    .requestMatchers("/js/**")
                    .permitAll()
                    //images
                    .requestMatchers("/web_images/**")
                    .permitAll()

                    //Translation strings (xml)
                    .requestMatchers("/i18n/**")
                    .permitAll()



                    //Allow forward of all Errors.
                    //ASYNC is the container re-dispatching a request the app answered
                    //asynchronously (the admin SSE stream) once that response ends. It carries no
                    //SecurityContext - JwtAuthFilter is a OncePerRequestFilter and does not run on
                    //async dispatches - so authorizing it again would deny a request that was
                    //already authorized on its initial dispatch, and the denial can't even be
                    //written because the streamed body is long since committed.
                    .dispatcherTypeMatchers(
                        DispatcherType.ERROR,
                        DispatcherType.FORWARD,
                        DispatcherType.ASYNC
                    )
                    .permitAll()

                    //Any other request needs to be authenticated
                    .anyRequest()
                    .authenticated()
            }

            //Default error is unauthorized (401)
            .exceptionHandling { configurer ->
                configurer
                    .authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterAfter(rateLimitFilter, JwtAuthFilter::class.java)
            .build()
    }
}