package com.octo.api.contact

import com.octo.api.OctoApplication
import com.octo.api.contact.ContactController.ContactLead
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.function.Supplier

/** The anonymous lead-capture write (#315): accepted shapes land, junk is 400, the honeypot swallows silently. */
class ContactEndpointTest {
    private val recorded = mutableListOf<ContactLead>()

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withBean(ContactStore::class.java, Supplier { ContactStore { recorded += it } }, { it.isPrimary = true })
            .withPropertyValues(
                "octo.reports.poll=false",
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private val lead =
        """{"email": "jane@fund.com", "firstName": "Jane", "lastName": "Doe", "firm": "Acme Capital",
            "role": "COO", "aumBand": "$250M – $1B", "phone": "+1 555 000 1234", "message": "three funds"}"""

    @Test
    fun `an anonymous submission is recorded with the proxy-appended address, not a client-supplied one`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lead)
                        .header("X-Forwarded-For", "198.51.100.66, 203.0.113.7"),
                ).andExpect(status().isAccepted)
            assertThat(recorded.single().email).isEqualTo("jane@fund.com")
            assertThat(recorded.single().sourceIp).isEqualTo("203.0.113.7")
        }
    }

    @Test
    fun `an oversized forwarding header is capped to the source_ip column bound, not a failed write`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lead)
                        .header("X-Forwarded-For", "x".repeat(300)),
                ).andExpect(status().isAccepted)
            assertThat(recorded.single().sourceIp).hasSize(255)
        }
    }

    @Test
    fun `missing required fields and a malformed email are client errors`() {
        run { mvc ->
            mvc
                .perform(post("/api/v1/contact").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lead.replace("jane@fund.com", "not-an-email")),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/contact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lead.replace(""""Jane"""", """"${"x".repeat(121)}"""")),
                ).andExpect(status().isBadRequest)
            assertThat(recorded).isEmpty()
        }
    }

    @Test
    fun `a filled honeypot is accepted but never stored`() {
        val bot =
            """{"email": "bot@spam.example", "firstName": "B", "lastName": "T", "firm": "Spam Co",
                "role": "S", "aumBand": "$10B+", "website": "spam.example"}"""
        run { mvc ->
            mvc
                .perform(post("/api/v1/contact").contentType(MediaType.APPLICATION_JSON).content(bot))
                .andExpect(status().isAccepted)
            assertThat(recorded).isEmpty()
        }
    }

    @Test
    fun `the contact path is write-only anonymous — reads still require a token`() {
        run { mvc ->
            mvc.perform(get("/api/v1/contact")).andExpect(status().isForbidden)
        }
    }
}
