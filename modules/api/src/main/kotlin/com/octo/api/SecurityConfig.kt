package com.octo.api

import com.octo.api.access.TenantDirectory
import com.octo.api.access.persistence.TenantSettings
import com.octo.api.ingestion.HeliusWebhookAuthFilter
import jakarta.servlet.DispatcherType
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Auth boundary for the REST API (AGENTS.md: auth endpoints are T2).
 *
 * The service is a stateless resource server: requests are authenticated by bearer JWTs verified
 * against the issuer's JWKS (`AUTH_JWKS_URL`), with issuer validation when `AUTH_ISSUER` is set.
 * Actuator health (including the liveness and readiness probes) and info stay public so probes and the
 * compose healthcheck keep working; metrics and prometheus need a bearer token like everything else.
 *
 * When no JWKS URL is configured the chain still requires authentication on every endpoint —
 * there is no unauthenticated fallback, so a misconfigured environment fails closed.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {
    /**
     * Vendor callbacks cannot carry a user JWT, so the webhook path gets its own chain ahead
     * of the resource-server chain: it matches only `/api/v1/ingestion/webhooks/helius` and
     * authenticates the request by shared secret (`HELIUS_WEBHOOK_SECRET`). Every other
     * request falls through to the JWT chain below — this chain widens nothing.
     */
    @Bean
    @Order(1)
    fun heliusWebhookFilterChain(
        http: HttpSecurity,
        env: Environment,
    ): SecurityFilterChain {
        http
            .securityMatcher("/api/v1/ingestion/webhooks/helius")
            // Stateless shared-secret auth — no cookies, no browser ambient credentials, so no
            // CSRF surface (CodeQL spring-disabled-csrf-protection is a false positive here).
            // Marker on its own line: a `codeql[...]` comment only covers the next line.
            // codeql[java/spring-disabled-csrf-protection]
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // An exception triggers an ERROR dispatch to /error with the SecurityContext
                // already cleared; denying it would mask every failure as 403, including the
                // filter's own sendError(401) for a missing or wrong webhook secret.
                it
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }.addFilterBefore(
                HeliusWebhookAuthFilter(env.getProperty("HELIUS_WEBHOOK_SECRET")),
                UsernamePasswordAuthenticationFilter::class.java,
            )
        return http.build()
    }

    /**
     * Redis backing the per-tenant quota counters — exists only when `REDIS_HOST` is configured,
     * so a deployment without the cache tier keeps working (the limiter simply isn't registered).
     */
    @Bean
    @ConditionalOnProperty(name = ["REDIS_HOST"])
    fun rateLimitRedisFactory(env: Environment): LettuceConnectionFactory =
        LettuceConnectionFactory(
            env.getRequiredProperty("REDIS_HOST"),
            env.getProperty("REDIS_PORT", "6379").toInt(),
        )

    @Bean
    @Order(2)
    fun securityFilterChain(
        http: HttpSecurity,
        env: Environment,
        redis: ObjectProvider<RedisConnectionFactory>,
        tenantDirectory: TenantDirectory,
        tenantSettings: TenantSettings,
    ): SecurityFilterChain {
        http
            // Stateless JWT Bearer — no cookies accepted, nothing ambient for CSRF to replay.
            // Marker on its own line: a `codeql[...]` comment only covers the next line.
            // codeql[java/spring-disabled-csrf-protection]
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // Same ERROR dispatch as the webhook chain: without this, a parse failure,
                // a type-mismatched path variable, or an internal error answers 403 instead
                // of its real status.
                it
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    // The landing-page lead form is the one anonymous business write (#315).
                    // Method-scoped so the path's future read surface still requires auth;
                    // abuse is bounded by validation, a honeypot, and the per-IP window in
                    // RateLimitFilter plus the Traefik edge limiter.
                    .requestMatchers(HttpMethod.POST, "/api/v1/contact")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }

        // Local testing only: with AUTH_DEV_BYPASS=true every request runs as the fixed
        // AUTH_DEV_SUBJECT identity — no issuer needed. Off by default; in a deployed
        // environment it must never run, so the boot refuses the combination rather than
        // trusting an env var audit (#270). A `prod`/`production` profile or a configured
        // issuer are the deployment signals — either one with the flag set fails the context.
        if (env.getProperty("AUTH_DEV_BYPASS")?.toBoolean() == true) {
            val prodProfile =
                env.getProperty("SPRING_PROFILES_ACTIVE").orEmpty().split(',').any {
                    it.trim().equals("prod", ignoreCase = true) || it.trim().equals("production", ignoreCase = true)
                }
            require(!prodProfile && env.getProperty("AUTH_JWKS_URL").isNullOrBlank()) {
                "AUTH_DEV_BYPASS cannot run alongside SPRING_PROFILES_ACTIVE=prod or a configured AUTH_JWKS_URL"
            }
            http.addFilterBefore(
                DevSubjectAuthFilter(env.getProperty("AUTH_DEV_SUBJECT")),
                UsernamePasswordAuthenticationFilter::class.java,
            )
        }

        // Per-tenant quota counts authenticated traffic only, so it runs after the bearer token
        // has been verified — a forged X-Tenant-Id cannot reach somebody else's counter because
        // the tenant comes from resolved membership, not the request.
        redis.ifAvailable { factory ->
            http.addFilterAfter(
                RateLimitFilter(
                    StringRedisTemplate(factory),
                    tenantSettings,
                    tenantDirectory,
                    env.getProperty("octo.rate-limit.default-per-minute", Int::class.java, 120),
                ),
                BearerTokenAuthenticationFilter::class.java,
            )
        }

        val jwksUri = env.getProperty("AUTH_JWKS_URL")?.takeIf(String::isNotBlank)
        if (jwksUri != null) {
            // A configured JWKS with issuer/audience left blank used to authenticate any token
            // the JWKS could verify — expiry and a non-blank subject, nothing tying it to this
            // deployment's issuer or intended audience (#319). Fail closed at boot instead of
            // silently skipping those checks, same posture as the AUTH_DEV_BYPASS guard above.
            require(!env.getProperty("AUTH_ISSUER").isNullOrBlank() && !env.getProperty("AUTH_AUDIENCE").isNullOrBlank()) {
                "AUTH_JWKS_URL is configured but AUTH_ISSUER and/or AUTH_AUDIENCE are blank — both are required " +
                    "once a JWKS is set, otherwise issuer/audience validation is silently skipped"
            }
            // Supabase GoTrue signs ES256 (JWT_KEYS/JWT_JWKS keypair); the
            // builder defaults to expecting RS256, so the algorithm must be
            // declared or every token is rejected as "another algorithm
            // expected" regardless of which keys the JWKS serves.
            val decoder =
                NimbusJwtDecoder
                    .withJwkSetUri(jwksUri)
                    .jwsAlgorithm(SignatureAlgorithm.ES256)
                    .build()
            decoder.setJwtValidator(bearerTokenValidator(env))
            http.oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { jwt -> jwt.decoder(decoder) }
            }
        }
        return http.build()
    }
}

/**
 * Bearer-token validation for the resource-server chain: expiry always, the issuer when
 * `AUTH_ISSUER` is configured, and the audience when `AUTH_AUDIENCE` is — GoTrue signs
 * `aud: "authenticated"` (`GOTRUE_JWT_AUD`), so a token minted by the same issuer for a
 * different audience still fails here.
 */
internal fun bearerTokenValidator(env: Environment): OAuth2TokenValidator<Jwt> {
    val validators =
        mutableListOf<OAuth2TokenValidator<Jwt>>(
            JwtTimestampValidator(),
            // Every downstream controller reads `jwt.subject` — a subjectless JWT (e.g. a
            // publishable anon token signed by the same JWKS) must fail here, not NPE there.
            JwtClaimValidator<Any>(JwtClaimNames.SUB) { sub -> sub is String && sub.isNotBlank() },
        )
    env
        .getProperty("AUTH_ISSUER")
        ?.takeIf(String::isNotBlank)
        ?.let { validators += JwtIssuerValidator(it) }
    env
        .getProperty("AUTH_AUDIENCE")
        ?.takeIf(String::isNotBlank)
        ?.let { audience ->
            // JWT `aud` may be a string or a list; GoTrue emits the string form.
            validators +=
                JwtClaimValidator<Any>(JwtClaimNames.AUD) { aud ->
                    when (aud) {
                        is String -> aud == audience
                        is Collection<*> -> audience in aud
                        else -> false
                    }
                }
        }
    return DelegatingOAuth2TokenValidator(validators)
}
