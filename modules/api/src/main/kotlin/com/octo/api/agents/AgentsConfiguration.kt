package com.octo.api.agents

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Wires the sidecar client. `OCTO_AGENTS_BASE_URL` unset means no sidecar is deployed alongside —
 * the bean still exists (controllers inject it unconditionally) but fails on use with
 * [AgentsUnavailableException], which the edge maps to 503 rather than letting a misconfiguration
 * look like a client error.
 */
@Configuration(proxyBeanMethods = false)
class AgentsConfiguration {
    @Bean
    fun jdkAgentsClient(env: Environment): AgentsClient {
        val baseUrl =
            env.getProperty("OCTO_AGENTS_BASE_URL")?.takeIf(String::isNotBlank)
                ?: return AgentsClient { _, _ ->
                    throw AgentsUnavailableException(IllegalStateException("OCTO_AGENTS_BASE_URL is not configured"))
                }
        return JdkAgentsClient(baseUrl, env.getProperty("OCTO_AGENTS_TOKEN") ?: "")
    }
}
