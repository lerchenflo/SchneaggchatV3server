package com.lerchenflo.schneaggchatv3server.core.security

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
    private val jwtAuthFilter: JwtAuthFilter
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
                    .requestMatchers("/public/**")
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

                    //Allow websockets
                    //.requestMatchers("/ws")
                    //.permitAll()


                    //Allow forward of all Errors
                    .dispatcherTypeMatchers(
                        DispatcherType.ERROR,
                        DispatcherType.FORWARD
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
            .build()
    }
}