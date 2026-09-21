package com.mesta.asset.api

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain

/**
 * Auth boundary for the REST API (AGENTS.md: auth endpoints are T2).
 *
 * The service is a stateless resource server: requests are authenticated by bearer JWTs verified
 * against the issuer's JWKS (`AUTH_JWKS_URL`), with issuer validation when `AUTH_ISSUER` is set.
 * Actuator health/info stay public so liveness probes and the compose healthcheck keep working.
 *
 * When no JWKS URL is configured the chain still requires authentication on every endpoint —
 * there is no unauthenticated fallback, so a misconfigured environment fails closed.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        env: Environment,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .requestMatchers("/actuator/health", "/actuator/info")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }

        val jwksUri = env.getProperty("AUTH_JWKS_URL")?.takeIf(String::isNotBlank)
        if (jwksUri != null) {
            val decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build()
            env
                .getProperty("AUTH_ISSUER")
                ?.takeIf(String::isNotBlank)
                ?.let { decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(it)) }
            http.oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { jwt -> jwt.decoder(decoder) }
            }
        }
        return http.build()
    }
}
