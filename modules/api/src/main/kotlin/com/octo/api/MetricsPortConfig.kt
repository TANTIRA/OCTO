package com.octo.api

import jakarta.servlet.DispatcherType
import org.apache.catalina.connector.Connector
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * Internal scrape port for `/actuator/prometheus` (#338).
 *
 * The public port keeps actuator exactly as it was: health and info anonymous, metrics and
 * prometheus behind a bearer JWT — a scraper has no user to mint one for. Instead of a service
 * account, a second Tomcat connector listens on `octo.metrics.port`. The compose files publish no
 * host port for it and Traefik routes only the domain's port 8080, so it is reachable solely from
 * containers on the stack's networks (the OTEL collector).
 *
 * A request is attributed to this port by the socket's local port, which a client cannot forge —
 * no Host or X-Forwarded-Port header is consulted. On that port anonymous GET `/actuator/prometheus`
 * is the only thing allowed; everything else, the business API included, is denied, so the extra
 * connector widens nothing beyond the scrape.
 *
 * Unset `octo.metrics.port` (e.g. a context that skips application.yml) means no connector and no
 * chain — the endpoint stays authenticated-only.
 */
@Configuration
@ConditionalOnProperty("octo.metrics.port")
class MetricsPortConfig(
    @Value("\${octo.metrics.port}") private val metricsPort: Int,
    @Value("\${server.port:8080}") serverPort: Int,
) {
    init {
        require(metricsPort in 1..65535 && metricsPort != serverPort) {
            "octo.metrics.port must be a fixed port (1-65535) different from server.port ($serverPort), was $metricsPort"
        }
    }

    @Bean
    fun metricsConnector(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            factory.addAdditionalTomcatConnectors(Connector().apply { port = metricsPort })
        }

    /** Ahead of the webhook (1) and JWT (2) chains so a request on the scrape port never reaches them. */
    @Bean
    @Order(0)
    fun metricsPortFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher(RequestMatcher { it.localPort == metricsPort })
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                    .permitAll()
                    .anyRequest()
                    .denyAll()
            }
        return http.build()
    }
}
