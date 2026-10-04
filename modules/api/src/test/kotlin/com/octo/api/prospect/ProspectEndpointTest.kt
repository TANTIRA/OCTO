package com.octo.api.prospect

import com.octo.api.OctoApplication
import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.api.agents.AgentsCallException
import com.octo.api.agents.AgentsClient
import com.octo.api.agents.AgentsUnavailableException
import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectSource
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.ProspectState
import com.octo.dealsourcing.next
import com.octo.dealsourcing.persistence.ProspectEventRow
import com.octo.dealsourcing.persistence.ProspectProvenance
import com.octo.dealsourcing.persistence.ProspectStore
import com.octo.dealsourcing.persistence.ScreeningRuleRow
import com.octo.dealsourcing.registered
import com.octo.persistence.TenantScope
import com.octo.workflow.Task
import com.octo.workflow.TaskEvent
import com.octo.workflow.TaskKind
import com.octo.workflow.TaskState
import com.octo.workflow.TaskStatus
import com.octo.workflow.opened
import com.octo.workflow.persistence.TaskProvenance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID
import java.util.function.Supplier
import com.octo.workflow.next as nextTask

/**
 * `/api/v1/prospects` end to end with a stubbed store and directory: members of the right role register
 * and advance, every other caller — viewer, non-member, no token — is denied, and the store's transition
 * errors surface as 409.
 */
class ProspectEndpointTest {
    private val member = UUID.randomUUID()
    private val approver = UUID.randomUUID()
    private val approver2 = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val admin = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val store = FakeProspectStore()
    private val tasks = FakeIcTasks()
    private val rules = FakeRules()
    private var agentsBehavior: (String, Map<String, Any>) -> Map<String, Any> = { _, _ ->
        mapOf("verdict" to mapOf("proceed" to true, "proceed_probability" to 0.9))
    }
    private val agents = AgentsClient { workflow, payload -> agentsBehavior(workflow, payload) }

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withBean(
                TenantDirectory::class.java,
                Supplier {
                    TenantDirectory { id ->
                        when (id) {
                            member -> listOf(TenantAccess(tenantId, "acme", TenantRole.ANALYST))
                            approver, approver2 -> listOf(TenantAccess(tenantId, "acme", TenantRole.APPROVER))
                            viewer -> listOf(TenantAccess(tenantId, "acme", TenantRole.VIEWER))
                            admin -> listOf(TenantAccess(tenantId, "acme", TenantRole.ADMIN))
                            else -> emptyList()
                        }
                    }
                },
                { it.isPrimary = true },
            ).withBean(
                ProspectStore::class.java,
                Supplier { store },
                { it.isPrimary = true },
            ).withBean(
                IcTasks::class.java,
                Supplier { tasks },
                { it.isPrimary = true },
            ).withBean(
                ScreeningRules::class.java,
                Supplier { rules },
                { it.isPrimary = true },
            ).withBean(
                AgentsClient::class.java,
                Supplier { agents },
                { it.isPrimary = true },
            ).withPropertyValues(
                "spring.autoconfigure.exclude=${DataSourceAutoConfiguration::class.qualifiedName},${FlywayAutoConfiguration::class.qualifiedName}",
            )

    private fun run(block: (MockMvc) -> Unit) {
        contextRunner.run { context ->
            block(MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build())
        }
    }

    private fun MockMvc.register(caller: UUID) =
        perform(
            post("/api/v1/prospects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantId":"$tenantId","name":"PT Acme","source":"referral","sector":"logistics"}""")
                .with(jwt().jwt { it.subject(caller.toString()) }),
        )

    private fun MockMvc.registered(): UUID {
        register(member).andExpect(status().isCreated)
        return store.states.keys.single()
    }

    @Test
    fun `an analyst registers and the prospect lands sourced`() {
        run { mvc ->
            mvc
                .register(member)
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("PT Acme"))
                .andExpect(jsonPath("$.source").value("referral"))
                .andExpect(jsonPath("$.stage").value("sourced"))
                .andExpect(jsonPath("$.decidedBy").doesNotExist())
        }
    }

    @Test
    fun `an analyst advances the prospect and a bad transition answers 409`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.stage").value("screening"))
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"ic-review"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isConflict)
        }
    }

    @Test
    fun `a member reads prospect and pipeline views while a non-member sees 404`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(get("/api/v1/prospects/$id").with(jwt().jwt { it.subject(viewer.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.stage").value("sourced"))
            mvc
                .perform(
                    get("/api/v1/prospects?tenantId=$tenantId&stage=sourced")
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$[0].id").value(id.toString()))
            mvc
                .perform(
                    get("/api/v1/prospects/$id").with(jwt().jwt { it.subject(UUID.randomUUID().toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    get("/api/v1/prospects?tenantId=${UUID.randomUUID()}&stage=sourced")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isNotFound)
        }
    }

    @Test
    fun `agent-screen passes the sidecar verdict through for members, not viewers, and 503s when it is down`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-screen")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.verdict.proceed").value(true))
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-screen")
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-screen")
                        .with(jwt().jwt { it.subject(UUID.randomUUID().toString()) }),
                ).andExpect(status().isNotFound)

            agentsBehavior = { _, _ -> throw AgentsUnavailableException(java.io.IOException("down")) }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-screen")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isServiceUnavailable)
        }
    }

    @Test
    fun `agent-ic-memo passes the sidecar result through for members, not viewers, and 503s when it is down`() {
        run { mvc ->
            val id = mvc.registered()
            agentsBehavior = { workflow, _ ->
                mapOf(
                    "workflow_seen" to workflow,
                    "memo" to "ic memo prose",
                    "ic_review_requested" to true,
                )
            }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-ic-memo")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.workflow_seen").value("ic-memo"))
                .andExpect(jsonPath("$.ic_review_requested").value(true))
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-ic-memo")
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-ic-memo")
                        .with(jwt().jwt { it.subject(UUID.randomUUID().toString()) }),
                ).andExpect(status().isNotFound)

            agentsBehavior = { _, _ -> throw AgentsUnavailableException(java.io.IOException("down")) }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-ic-memo")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isServiceUnavailable)
        }
    }

    @Test
    fun `agent-due-diligence calls the due-diligence workflow for members and maps sidecar failures`() {
        run { mvc ->
            val id = mvc.registered()
            var calls = 0
            agentsBehavior = { workflow, payload ->
                calls += 1
                mapOf(
                    "workflow_seen" to workflow,
                    "prospect_seen" to payload.getValue("prospect_id"),
                    "tenant_seen" to payload.getValue("tenant_id"),
                    "status" to "completed",
                )
            }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-due-diligence")
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-due-diligence")
                        .with(jwt().jwt { it.subject(UUID.randomUUID().toString()) }),
                ).andExpect(status().isNotFound)
            assertThat(calls).isZero()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-due-diligence")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.workflow_seen").value("due-diligence"))
                .andExpect(jsonPath("$.prospect_seen").value(id.toString()))
                .andExpect(jsonPath("$.tenant_seen").value(tenantId.toString()))
            assertThat(calls).isEqualTo(1)

            agentsBehavior = { _, _ -> throw AgentsUnavailableException(java.io.IOException("down")) }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-due-diligence")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isServiceUnavailable)

            agentsBehavior = { _, _ -> throw AgentsCallException(503, "flag off") }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-due-diligence")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isServiceUnavailable)

            agentsBehavior = { _, _ -> throw AgentsCallException(500, "model registry miss") }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/agent-due-diligence")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadGateway)
        }
    }

    @Test
    fun `dd-evidence opens one task per workstream, reuses on retry, and completes through the task endpoint`() {
        run { mvc ->
            val id = mvc.registered()
            val taskId =
                mvc
                    .perform(
                        post("/api/v1/prospects/$id/dd-evidence")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"workstream":"market","summary":"gap in demand evidence"}""")
                            .with(jwt().jwt { it.subject(member.toString()) }),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.opened").value(true))
                    .andReturn()
                    .response.contentAsString
                    .let {
                        com.fasterxml.jackson.databind
                            .ObjectMapper()
                            .readTree(it)["taskId"]
                            .asText()
                    }

            // A retried run on the same workstream reuses the open task instead of duplicating.
            mvc
                .perform(
                    post("/api/v1/prospects/$id/dd-evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"workstream":"market","summary":"retried"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.opened").value(false))

            // The task decides through the same endpoint as the checklist — the dd: subject binds.
            mvc
                .perform(
                    post("/api/v1/prospects/$id/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"completed","rationale":"evidence gathered"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("completed"))

            // Viewers and unknown callers never write; a bad workstream is a 400, not a 500.
            mvc
                .perform(
                    post("/api/v1/prospects/$id/dd-evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"workstream":"Legal","summary":"s"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/dd-evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"workstream":"ops","summary":"s"}""")
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isNotFound)
        }
    }

    @Test
    fun `viewers never write, a blank rationale is 400, and no token is refused`() {
        run { mvc ->
            val id = mvc.registered()
            mvc.register(viewer).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"passed"}""")
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"passed","rationale":"  "}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            // #504: an oversized rationale is a 400, not a permanent resident of the event log.
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"passed","rationale":"${"x".repeat(10_001)}"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(get("/api/v1/prospects/$id"))
                .andExpect(status().isForbidden)
        }
    }

    @Test
    fun `a transition rationale at the limit is stored and one character over is refused`() {
        val atLimit = "r".repeat(RATIONALE_LIMIT)
        val over = "r".repeat(RATIONALE_LIMIT + 1)
        run { mvc ->
            val refused = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$refused/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"passed","rationale":"$over"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            assertThat(store.eventRows[refused].orEmpty()).isEmpty()

            mvc.register(member).andExpect(status().isCreated)
            val accepted = store.states.keys.single { it != refused }
            mvc
                .perform(
                    post("/api/v1/prospects/$accepted/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"passed","rationale":"$atLimit"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.stage").value("passed"))
            assertThat(
                store.eventRows
                    .getValue(accepted)
                    .single()
                    .rationale,
            ).hasSize(RATIONALE_LIMIT)
        }
    }

    @Test
    fun `a task rationale and assignee at the limit are accepted and one character over is refused`() {
        val atRationale = "r".repeat(RATIONALE_LIMIT)
        val overRationale = "r".repeat(RATIONALE_LIMIT + 1)
        val atAssignee = "a".repeat(ASSIGNEE_LIMIT)
        val overAssignee = "a".repeat(ASSIGNEE_LIMIT + 1)
        run { mvc ->
            val id = mvc.registered()
            for (stage in listOf("screening", "due-diligence", "ic-review")) {
                mvc
                    .perform(
                        post("/api/v1/prospects/$id/transition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"to":"$stage"}""")
                            .with(jwt().jwt { it.subject(member.toString()) }),
                    ).andExpect(status().isOk)
            }
            mvc
                .perform(post("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(member.toString()) }))
                .andExpect(status().isAccepted)
            val approval = tasks.opened().single { it.kind == TaskKind.APPROVAL }.id
            val evidence = tasks.opened().single { it.kind == TaskKind.EVIDENCE_REQUEST }.id

            mvc
                .perform(
                    post("/api/v1/prospects/$id/tasks/$approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"assigned","assignee":"$overAssignee"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/tasks/$approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"assigned","assignee":"$atAssignee"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.assignee").value(atAssignee))

            mvc
                .perform(
                    post("/api/v1/prospects/$id/tasks/$evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"completed","rationale":"$overRationale"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/tasks/$evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"completed","rationale":"$atRationale"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("completed"))
        }
    }

    @Test
    fun `invested needs an approved IC task on the prospect`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isConflict) // only an ic-review prospect gets an IC task

            for (stage in listOf("screening", "due-diligence", "ic-review")) {
                mvc
                    .perform(
                        post("/api/v1/prospects/$id/transition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"to":"$stage"}""")
                            .with(jwt().jwt { it.subject(member.toString()) }),
                    ).andExpect(status().isOk)
            }

            val taskId = UUID.randomUUID().also { tasks.openAt(it, id) }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk) // the review already in flight comes back, not a second task
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.taskStatus").value("open"))

            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"invested","rationale":"corridor thesis","taskId":"$taskId"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isConflict) // task still open — the gate holds

            tasks.approve(taskId)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"invested","rationale":"corridor thesis","taskId":"$taskId"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.stage").value("invested"))
                .andExpect(jsonPath("$.decidedBy").value(member.toString()))
        }
    }

    @Test
    fun `an approval superseded by a later IC review no longer authorizes invested`() {
        run { mvc ->
            val id = mvc.registered()
            for (stage in listOf("screening", "due-diligence", "ic-review")) {
                mvc
                    .perform(
                        post("/api/v1/prospects/$id/transition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"to":"$stage"}""")
                            .with(jwt().jwt { it.subject(member.toString()) }),
                    ).andExpect(status().isOk)
            }
            val stale = UUID.randomUUID().also { tasks.openAt(it, id) }
            tasks.approve(stale)
            val latest = UUID.randomUUID().also { tasks.openAt(it, id) } // re-review, still undecided

            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"invested","rationale":"corridor thesis","taskId":"$stale"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isConflict) // the IC's current call is the re-review, not the old approval

            tasks.approve(latest)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"invested","rationale":"corridor thesis","taskId":"$latest"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.stage").value("invested"))
        }
    }

    @Test
    fun `an IC task is decided by an approver, never its requester, never an analyst`() {
        run { mvc ->
            val id = mvc.registered()
            for (stage in listOf("screening", "due-diligence", "ic-review")) {
                mvc
                    .perform(
                        post("/api/v1/prospects/$id/transition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"to":"$stage"}""")
                            .with(jwt().jwt { it.subject(member.toString()) }),
                    ).andExpect(status().isOk)
            }
            mvc // the approver asks for the review — anyone non-viewer may ask
                .perform(
                    post("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(approver.toString()) }),
                ).andExpect(status().isAccepted)
            val taskId = tasks.opened().single { it.kind == TaskKind.APPROVAL }.id

            mvc // the requester can never decide their own approval — segregation of duties
                .perform(
                    post("/api/v1/prospects/$id/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"approved"}""")
                        .with(jwt().jwt { it.subject(approver.toString()) }),
                ).andExpect(status().isConflict)
            mvc // an admin is segregated from approval duties — the IC gate is the approver's alone
                .perform(
                    post("/api/v1/prospects/$id/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"approved"}""")
                        .with(jwt().jwt { it.subject(admin.toString()) }),
                ).andExpect(status().isNotFound)
            mvc // an analyst lacks the gate role — deciding needs an approver
                .perform(
                    post("/api/v1/prospects/$id/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"approved"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isNotFound)
            mvc // a viewer holds no write on the task
                .perform(
                    post("/api/v1/prospects/$id/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"approved"}""")
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"approved","rationale":"conviction"}""")
                        .with(jwt().jwt { it.subject(approver2.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.kind").value("approval"))
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.decidedBy").value(approver2.toString()))

            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"invested","rationale":"corridor thesis","taskId":"$taskId"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.stage").value("invested"))
        }
    }

    @Test
    fun `the IC review read returns the latest approval on the prospect, to any role in its tenant only`() {
        run { mvc ->
            val id = mvc.registered()
            mvc // no review yet
                .perform(get("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(member.toString()) }))
                .andExpect(status().isNotFound)
            for (stage in listOf("screening", "due-diligence", "ic-review")) {
                mvc
                    .perform(
                        post("/api/v1/prospects/$id/transition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"to":"$stage"}""")
                            .with(jwt().jwt { it.subject(member.toString()) }),
                    ).andExpect(status().isOk)
            }
            mvc
                .perform(post("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(member.toString()) }))
                .andExpect(status().isAccepted)
            val first = tasks.opened().single { it.kind == TaskKind.APPROVAL }.id

            for (reader in listOf(member, approver, viewer)) {
                mvc
                    .perform(get("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(reader.toString()) }))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.taskId").value(first.toString()))
                    .andExpect(jsonPath("$.taskStatus").value("open"))
                    .andExpect(jsonPath("$.requestedBy").value(member.toString()))
                    .andExpect(jsonPath("$.decidedBy").doesNotExist())
            }
            mvc // a caller with no role in the prospect's tenant never learns the review exists
                .perform(get("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(UUID.randomUUID().toString()) }))
                .andExpect(status().isNotFound)

            mvc
                .perform(
                    post("/api/v1/prospects/$id/tasks/$first")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"rejected","rationale":"thin diligence"}""")
                        .with(jwt().jwt { it.subject(approver.toString()) }),
                ).andExpect(status().isOk)
            mvc // a decided review is history: asking again opens a fresh one, and the read follows it
                .perform(post("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(member.toString()) }))
                .andExpect(status().isAccepted)
            val second =
                tasks
                    .opened()
                    .filter { it.kind == TaskKind.APPROVAL }
                    .single { it.id != first }
                    .id
            tasks.approve(second)
            mvc
                .perform(get("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(member.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.taskId").value(second.toString()))
                .andExpect(jsonPath("$.taskStatus").value("approved"))
                .andExpect(jsonPath("$.decidedBy").value("ic-member"))
        }
    }

    @Test
    fun `gate events on an approval task are approver-only while routing stays a working action`() {
        run { mvc ->
            val id = mvc.registered()
            for (stage in listOf("screening", "due-diligence", "ic-review")) {
                mvc
                    .perform(
                        post("/api/v1/prospects/$id/transition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"to":"$stage"}""")
                            .with(jwt().jwt { it.subject(member.toString()) }),
                    ).andExpect(status().isOk)
            }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isAccepted)
            val taskId = tasks.opened().single { it.kind == TaskKind.APPROVAL }.id

            for (event in listOf("approved", "rejected", "rework-requested", "cancelled")) {
                mvc // every gate decision is refused for an analyst — the rationale is valid so it is the role that fails
                    .perform(
                        post("/api/v1/prospects/$id/tasks/$taskId")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"event":"$event","rationale":"r"}""")
                            .with(jwt().jwt { it.subject(member.toString()) }),
                    ).andExpect(status().isNotFound)
            }
            mvc // assigning is routing, not deciding — a working action stays open to an analyst
                .perform(
                    post("/api/v1/prospects/$id/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"assigned","assignee":"ic-chair"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.assignee").value("ic-chair"))
        }
    }

    @Test
    fun `the evidence checklist completes through the task endpoint`() {
        run { mvc ->
            val id = mvc.registered()
            for (stage in listOf("screening", "due-diligence")) {
                mvc
                    .perform(
                        post("/api/v1/prospects/$id/transition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"to":"$stage"}""")
                            .with(jwt().jwt { it.subject(member.toString()) }),
                    ).andExpect(status().isOk)
            }
            val taskId = tasks.opened().single().id
            mvc // an analyst completes a working task — the gate applies to approvals, not checklists
                .perform(
                    post("/api/v1/prospects/$id/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"completed","rationale":"DDQ received"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.kind").value("evidence-request"))
                .andExpect(jsonPath("$.status").value("completed"))
        }
    }

    @Test
    fun `the task endpoint refuses a task on another prospect, bad input, and unknown tasks`() {
        run { mvc ->
            val id = mvc.registered()
            mvc.register(member).andExpect(status().isCreated)
            val other = store.states.keys.last()
            val taskId = UUID.randomUUID().also { tasks.openAt(it, other) }
            mvc // the task is bound to `other`, not `id` — 404, not a leak
                .perform(
                    post("/api/v1/prospects/$id/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"approved"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/tasks/${UUID.randomUUID()}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"approved"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"nonsense"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isNotFound) // subject binding is checked before the event parses
            mvc
                .perform(
                    post("/api/v1/prospects/$other/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"nonsense"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/prospects/$other/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"rejected"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest) // a rejection without a rationale is refused
            // #504: oversized rationale and assignee are 400s, not permanent event-log rows.
            mvc
                .perform(
                    post("/api/v1/prospects/$other/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"rejected","rationale":"${"x".repeat(10_001)}"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/prospects/$other/tasks/$taskId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"event":"assigned","assignee":"${"x".repeat(201)}"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `a screen racing a transition answers 409, not 500`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/screening-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"tenantId":"$tenantId","ruleId":"mandate","name":"Mandate","criteria":{"sectors":["saas"]}}""",
                        ).with(jwt().jwt { it.subject(approver.toString()) }),
                ).andExpect(status().isCreated)
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            store.failAppend = IllegalArgumentException("lost the race")
            mvc
                .perform(
                    post("/api/v1/prospects/$id/screen").with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isConflict)
        }
    }

    /** Replays through the real state machine so tests exercise production transition semantics. */
    private class FakeProspectStore : ProspectStore {
        val states = linkedMapOf<UUID, ProspectState>()
        var failAppend: Throwable? = null

        /** The racing caller's committed landings — replayed through the real machine, with their claim rows, before [failAppend] throws. */
        var appendFailureLands: (() -> List<ProspectEvent>)? = null

        override fun create(
            prospect: Prospect,
            actor: String,
            provenance: ProspectProvenance,
            scope: TenantScope,
        ) {
            states[prospect.id] = registered(prospect)
        }

        override fun load(
            id: UUID,
            scope: TenantScope,
        ): ProspectState? = states[id]

        val eventRows = mutableMapOf<UUID, MutableList<ProspectEventRow>>()

        override fun importBatch(
            prospects: List<Prospect>,
            actor: String,
            provenance: ProspectProvenance,
            scope: TenantScope,
        ): List<UUID> {
            val seen =
                states.values
                    .map { Triple(it.prospect.tenantId, it.prospect.source, it.prospect.sourceRef) }
                    .toMutableSet()
            return prospects
                .filter { it.sourceRef == null || seen.add(Triple(it.tenantId, it.source, it.sourceRef)) }
                .onEach { states[it.id] = registered(it) }
                .map { it.id }
        }

        override fun history(
            id: UUID,
            scope: TenantScope,
        ): List<ProspectEventRow>? = eventRows[id] ?: states[id]?.let { emptyList() }

        override fun listAtStage(
            tenantId: UUID,
            stage: ProspectStage,
            limit: Int,
            offset: Int,
            scope: TenantScope,
        ): List<ProspectState> =
            states.values
                .filter { it.prospect.tenantId == tenantId && it.stage == stage }
                .drop(offset)
                .take(limit)

        override fun append(
            prospectId: UUID,
            event: ProspectEvent,
            provenance: ProspectProvenance,
            scope: TenantScope,
        ): ProspectState {
            failAppend?.let {
                appendFailureLands?.invoke()?.forEach { land -> apply(prospectId, land, provenance) }
                throw it
            }
            return apply(prospectId, event, provenance)
        }

        private fun apply(
            prospectId: UUID,
            event: ProspectEvent,
            provenance: ProspectProvenance,
        ): ProspectState {
            val current = states[prospectId] ?: throw NoSuchElementException("no prospect $prospectId")
            val next = current.next(event)
            states[prospectId] = next
            eventRows
                .getOrPut(prospectId) { mutableListOf() }
                .add(
                    ProspectEventRow(
                        seq = (eventRows[prospectId]?.size ?: 0) + 1L,
                        eventType =
                            when (event) {
                                is ProspectEvent.Advanced -> "advanced"
                                is ProspectEvent.Passed -> "passed"
                                is ProspectEvent.Invested -> "invested"
                            },
                        stageFrom = current.stage,
                        stageTo = next.stage,
                        actor = event.actor,
                        rationale = (event as? ProspectEvent.Passed)?.rationale ?: (event as? ProspectEvent.Invested)?.rationale,
                        occurredAt = event.at,
                        recordedAt = java.time.Instant.now(),
                        correlationId = provenance.correlationId,
                        taskId = (event as? ProspectEvent.Invested)?.taskId ?: (event as? ProspectEvent.Advanced)?.taskId,
                    ),
                )
            return next
        }
    }

    @Test
    fun `a screening reject passes the prospect with the violated constraint as rationale`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/screening-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"tenantId":"$tenantId","ruleId":"mandate","name":"Mandate","criteria":{"sectors":["saas"]}}""",
                        ).with(jwt().jwt { it.subject(approver.toString()) }),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.version").value(1))
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/screen").with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.verdict").value("reject"))
                .andExpect(jsonPath("$.reasons[0]").value("[Mandate] sector 'logistics' is outside the mandate [saas]"))
                .andExpect(jsonPath("$.stage").value("passed"))
        }
    }

    @Test
    fun `a screen without rules reviews and opens a review task`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/screen").with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.verdict").value("review"))
                .andExpect(jsonPath("$.reasons[0]").value("no active screening rule for the tenant"))
                .andExpect(jsonPath("$.reviewTaskId").exists())
                .andExpect(jsonPath("$.stage").value("screening"))
        }
    }

    @Test
    fun `landing in due-diligence opens the evidence checklist task`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            assertThat(tasks.opened()).isEmpty()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"due-diligence"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.stage").value("due-diligence"))
            val task = tasks.opened().single()
            assertThat(task.kind).isEqualTo(TaskKind.EVIDENCE_REQUEST)
            assertThat(task.subjectId).isEqualTo(id.toString())
            mvc
                .perform(get("/api/v1/prospects/$id/events").with(jwt().jwt { it.subject(member.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[1].taskId").value(task.id.toString())) // the landing records the checklist it claims
        }
    }

    @Test
    fun `the event trail reads back actor, rationale and provenance in append order`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"passed","rationale":"off-mandate"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            mvc
                .perform(get("/api/v1/prospects/$id/events").with(jwt().jwt { it.subject(viewer.toString()) }))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].eventType").value("advanced"))
                .andExpect(jsonPath("$[0].stageFrom").value("sourced"))
                .andExpect(jsonPath("$[0].stageTo").value("screening"))
                .andExpect(jsonPath("$[0].actor").value(member.toString()))
                .andExpect(jsonPath("$[1].eventType").value("passed"))
                .andExpect(jsonPath("$[1].rationale").value("off-mandate"))
                .andExpect(jsonPath("$[1].correlationId").exists())
            mvc
                .perform(
                    get("/api/v1/prospects/$id/events")
                        .with(jwt().jwt { it.subject(UUID.randomUUID().toString()) }),
                ).andExpect(status().isNotFound)
        }
    }

    @Test
    fun `a bulk import dedupes on the external ref and refuses bad input`() {
        run { mvc ->
            val body =
                """{"tenantId":"$tenantId","items":[""" +
                    """{"name":"A","source":"crm","sourceRef":"crm-1"},""" +
                    """{"name":"B","source":"crm","sourceRef":"crm-1"},""" +
                    """{"name":"C","source":"referral"}]}"""
            mvc
                .perform(
                    post("/api/v1/prospects/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.duplicates").value(1))
            mvc
                .perform(
                    post("/api/v1/prospects/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId","items":[{"name":"X","source":"nosuch"}]}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `a bulk import over the batch limit is refused`() {
        run { mvc ->
            val items = (1..501).joinToString(",") { """{"name":"P$it","source":"crm"}""" }
            mvc
                .perform(
                    post("/api/v1/prospects/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId","items":[$items]}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `ic-review is idempotent — the task in flight is returned, not duplicated`() {
        run { mvc ->
            val id = mvc.registered()
            for (stage in listOf("screening", "due-diligence", "ic-review")) {
                mvc
                    .perform(
                        post("/api/v1/prospects/$id/transition")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"to":"$stage"}""")
                            .with(jwt().jwt { it.subject(member.toString()) }),
                    ).andExpect(status().isOk)
            }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isAccepted)
            val taskId = tasks.opened().single { it.kind == TaskKind.APPROVAL }.id
            mvc // asking again returns the review already in flight — never a second task
                .perform(
                    post("/api/v1/prospects/$id/ic-review").with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.taskStatus").value("open"))
            assertThat(tasks.opened().count { it.kind == TaskKind.APPROVAL }).isEqualTo(1)
        }
    }

    @Test
    fun `a screening review is idempotent — repeated screens return the open task`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            var reviewTaskId: UUID? = null
            repeat(2) {
                val result =
                    mvc
                        .perform(
                            post("/api/v1/prospects/$id/screen").with(jwt().jwt { it.subject(member.toString()) }),
                        ).andExpect(status().isOk)
                        .andExpect(jsonPath("$.verdict").value("review"))
                        .andReturn()
                val body = result.response.contentAsString
                val thisId = UUID.fromString(body.substringAfter("\"reviewTaskId\":\"").substringBefore('"'))
                if (reviewTaskId == null) reviewTaskId = thisId else assertThat(thisId).isEqualTo(reviewTaskId)
            }
            assertThat(tasks.opened()).hasSize(1)
        }
    }

    @Test
    fun `a rule_id outside the documented shape is a 400, not a 500 from the database`() {
        run { mvc ->
            for (bad in listOf("My Rule", "-bad", "x".repeat(65), "UPPER")) {
                mvc
                    .perform(
                        post("/api/v1/screening-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """{"tenantId":"$tenantId","ruleId":"$bad","name":"Mandate","criteria":{}}""",
                            ).with(jwt().jwt { it.subject(approver.toString()) }),
                    ).andExpect(status().isBadRequest)
            }
            mvc // inside the shape still lands
                .perform(
                    post("/api/v1/screening-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"tenantId":"$tenantId","ruleId":"esg.exclusions-2026","name":"ESG","criteria":{}}""",
                        ).with(jwt().jwt { it.subject(approver.toString()) }),
                ).andExpect(status().isCreated)
        }
    }

    @Test
    fun `rule writes are governance — an analyst gets 404 where an approver lands the write`() {
        run { mvc ->
            val define =
                """{"tenantId":"$tenantId","ruleId":"mandate","name":"Mandate","criteria":{"sectors":["saas"]}}"""
            mvc
                .perform(
                    post("/api/v1/screening-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(define)
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/screening-rules/mandate/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isNotFound)
            mvc
                .perform(
                    post("/api/v1/screening-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(define)
                        .with(jwt().jwt { it.subject(approver.toString()) }),
                ).andExpect(status().isCreated)
            mvc
                .perform(
                    post("/api/v1/screening-rules/mandate/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId"}""")
                        .with(jwt().jwt { it.subject(approver.toString()) }),
                ).andExpect(status().isOk)
        }
    }

    @Test
    fun `a criteria document naming an unknown field or an oversized input is a 400`() {
        run { mvc ->
            val oversized = (1..101).joinToString(",") { "\"s$it\"" }
            val bodies =
                listOf(
                    // 'sector' is a typo of 'sectors' — ignored at read, so refused at write
                    """{"tenantId":"$tenantId","ruleId":"mandate","name":"Mandate","criteria":{"sector":["saas"]}}""",
                    // an empty allowed set matches nothing — a reject-everything rule, not unconstrained
                    """{"tenantId":"$tenantId","ruleId":"mandate","name":"Mandate","criteria":{"sectors":[]}}""",
                    """{"tenantId":"$tenantId","ruleId":"mandate","name":"Mandate","criteria":{"sectors":[$oversized]}}""",
                    """{"tenantId":"$tenantId","ruleId":"mandate","name":"${"n".repeat(301)}","criteria":{}}""",
                )
            for (body in bodies) {
                mvc
                    .perform(
                        post("/api/v1/screening-rules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .with(jwt().jwt { it.subject(approver.toString()) }),
                    ).andExpect(status().isBadRequest)
            }
        }
    }

    @Test
    fun `a prospect registration or import item beyond its field bounds is a 400`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId","name":"${"n".repeat(301)}","source":"crm"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
            mvc
                .perform(
                    post("/api/v1/prospects/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"tenantId":"$tenantId","items":[{"name":"A","source":"crm","sourceRef":"${"r".repeat(201)}"}]}""",
                        ).with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `the pipeline read is paged and bad paging is refused`() {
        run { mvc ->
            val id = mvc.registered()
            mvc.register(member).andExpect(status().isCreated)
            mvc
                .perform(
                    get("/api/v1/prospects?tenantId=$tenantId&stage=sourced&limit=1")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
            mvc
                .perform(
                    get("/api/v1/prospects?tenantId=$tenantId&stage=sourced&limit=1&offset=1")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(org.hamcrest.Matchers.not(id.toString())))
            for (bad in listOf("limit=0", "limit=501", "offset=-1")) {
                mvc
                    .perform(
                        get("/api/v1/prospects?tenantId=$tenantId&stage=sourced&$bad")
                            .with(jwt().jwt { it.subject(member.toString()) }),
                    ).andExpect(status().isBadRequest)
            }
        }
    }

    /** Task states keyed by id; `openAt` plants an approval task on the prospect, `approve` resolves it. */
    private class FakeIcTasks : IcTasks {
        private val states = mutableMapOf<UUID, TaskState>()

        override fun open(
            task: Task,
            provenance: TaskProvenance,
        ) {
            states[task.id] = opened(task)
        }

        override fun state(taskId: UUID): TaskState? = states[taskId]

        /** Replays through the real task state machine so endpoint tests exercise its rules. */
        override fun append(
            taskId: UUID,
            event: TaskEvent,
            provenance: TaskProvenance,
        ): TaskState {
            val current = states[taskId] ?: throw NoSuchElementException("no task $taskId")
            val next = current.nextTask(event)
            states[taskId] = next
            return next
        }

        override fun listForSubject(
            subjectType: String,
            subjectId: String,
        ): List<TaskState> =
            states.values
                .filter { it.task.subjectType == subjectType && it.task.subjectId == subjectId }
                .sortedBy { it.task.createdAt }

        /** Same dedupe contract as the store: a task of the kind already open wins, else this opens. */
        override fun openUnlessOpen(
            task: Task,
            provenance: TaskProvenance,
        ): TaskState =
            states.values
                .firstOrNull {
                    it.task.subjectType == task.subjectType && it.task.subjectId == task.subjectId &&
                        it.task.kind == task.kind && !it.status.terminal
                }
                ?: run {
                    open(task, provenance)
                    states.getValue(task.id)
                }

        fun openAt(
            taskId: UUID,
            prospectId: UUID,
        ) {
            open(
                Task(taskId, com.octo.workflow.TaskKind.APPROVAL, "prospect", prospectId.toString(), "requester", java.time.Instant.now()),
                TaskProvenance("test", UUID.randomUUID()),
            )
        }

        fun approve(taskId: UUID) {
            states.computeIfPresent(taskId) { _, s -> s.copy(status = TaskStatus.APPROVED, decidedBy = "ic-member") }
        }

        fun opened(): List<Task> = states.values.map { it.task }
    }

    @Test
    fun `a retired rule leaves the active set — the screen then has nothing to apply`() {
        run { mvc ->
            mvc
                .perform(
                    post("/api/v1/screening-rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"tenantId":"$tenantId","ruleId":"mandate","name":"Mandate","criteria":{"sectors":["saas"]}}""",
                        ).with(jwt().jwt { it.subject(approver.toString()) }),
                ).andExpect(status().isCreated)
            mvc // the tombstone is a version like any other
                .perform(
                    post("/api/v1/screening-rules/mandate/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId"}""")
                        .with(jwt().jwt { it.subject(approver.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.version").value(2))
            mvc // a rule the tenant never had is 404
                .perform(
                    post("/api/v1/screening-rules/never-defined/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId"}""")
                        .with(jwt().jwt { it.subject(approver.toString()) }),
                ).andExpect(status().isNotFound)
            mvc // a viewer holds no write
                .perform(
                    post("/api/v1/screening-rules/mandate/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tenantId":"$tenantId"}""")
                        .with(jwt().jwt { it.subject(viewer.toString()) }),
                ).andExpect(status().isNotFound)

            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            mvc // the only rule is retired — screening falls back to review, not to its last active version
                .perform(
                    post("/api/v1/prospects/$id/screen").with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.verdict").value("review"))
                .andExpect(jsonPath("$.reasons[0]").value("no active screening rule for the tenant"))
        }
    }

    @Test
    fun `a stored rule that does not parse reviews rather than failing the whole screen`() {
        run { mvc ->
            rules.plant(ScreeningRuleRow("mandate", 1, "Damaged", """{"sectors":"not-a-list"}"""))
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            mvc // out-of-band damage is a human problem, never a silent pass and never a 500
                .perform(
                    post("/api/v1/prospects/$id/screen").with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.verdict").value("review"))
                .andExpect(jsonPath("$.reasons[0]").value("[Damaged] the stored criteria could not be read"))
        }
    }

    @Test
    fun `a due-diligence transition that fails to append cancels the checklist it opened`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            store.failAppend = IllegalArgumentException("lost the race")
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"due-diligence"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isConflict)
            val task = tasks.opened().single() // the checklist opened, then was cancelled — never left orphaned
            assertThat(task.kind).isEqualTo(TaskKind.EVIDENCE_REQUEST)
            assertThat(tasks.state(task.id)?.status).isEqualTo(TaskStatus.CANCELLED)
        }
    }

    @Test
    fun `a due-diligence landing that won the race keeps the checklist the loser opened`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            // The racing caller's landing committed while this request was in flight — it deduped
            // onto the checklist this request opened and recorded the claim on its event.
            store.failAppend = IllegalArgumentException("lost the race")
            store.appendFailureLands = {
                listOf(
                    ProspectEvent.Advanced(
                        "the winning caller",
                        java.time.Instant.now(),
                        ProspectStage.SCREENING,
                        ProspectStage.DUE_DILIGENCE,
                        taskId = tasks.opened().single().id,
                    ),
                )
            }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"due-diligence"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isConflict)
            val task = tasks.opened().single() // claimed by the winning landing — it still serves
            assertThat(task.kind).isEqualTo(TaskKind.EVIDENCE_REQUEST)
            assertThat(tasks.state(task.id)?.status).isEqualTo(TaskStatus.OPEN)
        }
    }

    @Test
    fun `a checklist the winner claimed stays open after the prospect moved past due-diligence`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            // The winning landing claimed this request's task, then the pipeline moved on to
            // ic-review before the loser observed the failure — the claim still holds.
            store.failAppend = IllegalArgumentException("lost the race")
            store.appendFailureLands = {
                val at = java.time.Instant.now()
                listOf(
                    ProspectEvent.Advanced(
                        "the winning caller",
                        at,
                        ProspectStage.SCREENING,
                        ProspectStage.DUE_DILIGENCE,
                        taskId = tasks.opened().single().id,
                    ),
                    ProspectEvent.Advanced("the winning caller", at, ProspectStage.DUE_DILIGENCE, ProspectStage.IC_REVIEW),
                )
            }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"due-diligence"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isConflict)
            val task = tasks.opened().single()
            assertThat(task.kind).isEqualTo(TaskKind.EVIDENCE_REQUEST)
            assertThat(tasks.state(task.id)?.status).isEqualTo(TaskStatus.OPEN)
        }
    }

    @Test
    fun `a checklist minted after an earlier landing claimed and closed its own is still an orphan — cancelled`() {
        run { mvc ->
            val id = mvc.registered()
            // The real landing claimed its own checklist, which was gathered and completed during
            // due-diligence — the checklist this losing request mints was never claimed by anyone.
            val claimed = UUID.randomUUID()
            tasks.open(
                Task(claimed, TaskKind.EVIDENCE_REQUEST, "prospect", id.toString(), "the earlier caller", java.time.Instant.now()),
                TaskProvenance("test", UUID.randomUUID()),
            )
            tasks.append(
                claimed,
                TaskEvent.Completed("the earlier caller", java.time.Instant.now()),
                TaskProvenance("test", UUID.randomUUID()),
            )
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            store.failAppend = IllegalArgumentException("lost the race")
            store.appendFailureLands = {
                val at = java.time.Instant.now()
                listOf(
                    ProspectEvent.Advanced(
                        "the winning caller",
                        at,
                        ProspectStage.SCREENING,
                        ProspectStage.DUE_DILIGENCE,
                        taskId = claimed,
                    ),
                    ProspectEvent.Advanced("the winning caller", at, ProspectStage.DUE_DILIGENCE, ProspectStage.IC_REVIEW),
                )
            }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"due-diligence"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isConflict)
            val minted = tasks.opened().single { it.id != claimed } // the loser's fresh checklist — no landing named it
            assertThat(minted.kind).isEqualTo(TaskKind.EVIDENCE_REQUEST)
            assertThat(tasks.state(minted.id)?.status).isEqualTo(TaskStatus.CANCELLED)
            assertThat(tasks.state(claimed)?.status).isEqualTo(TaskStatus.COMPLETED) // the claimed checklist is untouched
        }
    }

    @Test
    fun `a checklist opened for a landing a pass beat to it is still an orphan — cancelled`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            // A terminal landing never claimed the checklist — dead prospect, dead ask.
            store.failAppend = IllegalArgumentException("lost the race")
            store.appendFailureLands = {
                listOf(ProspectEvent.Passed("the winning caller", java.time.Instant.now(), ProspectStage.SCREENING, "off-mandate"))
            }
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"due-diligence"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isConflict)
            val task = tasks.opened().single()
            assertThat(task.kind).isEqualTo(TaskKind.EVIDENCE_REQUEST)
            assertThat(tasks.state(task.id)?.status).isEqualTo(TaskStatus.CANCELLED)
        }
    }

    @Test
    fun `a due-diligence append the store drops still cleans the checklist it minted`() {
        run { mvc ->
            val id = mvc.registered()
            mvc
                .perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"screening"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                ).andExpect(status().isOk)
            // A store failure that is not a lost race still commits nothing: the minted checklist
            // gets the same cleanup, and the failure reaches the caller — never a silent 200.
            store.failAppend = IllegalStateException("the store dropped the append")
            assertThrows<Exception> {
                mvc.perform(
                    post("/api/v1/prospects/$id/transition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"to":"due-diligence"}""")
                        .with(jwt().jwt { it.subject(member.toString()) }),
                )
            }
            val task = tasks.opened().single()
            assertThat(task.kind).isEqualTo(TaskKind.EVIDENCE_REQUEST)
            assertThat(tasks.state(task.id)?.status).isEqualTo(TaskStatus.CANCELLED)
        }
    }

    /** Versioned rule rows like the store: `define` bumps per rule_id, `activeRules` takes the newest active. */
    private class FakeRules : ScreeningRules {
        private val defined = mutableListOf<Pair<ScreeningRuleRow, Boolean>>()

        override fun define(
            tenantId: UUID,
            ruleId: String,
            name: String,
            criteria: String,
            actor: String,
            provenance: ProspectProvenance,
            scope: TenantScope,
        ): Int {
            val version = (defined.filter { it.first.ruleId == ruleId }.maxOfOrNull { it.first.version } ?: 0) + 1
            defined += ScreeningRuleRow(ruleId, version, name, criteria) to true
            return version
        }

        override fun retire(
            tenantId: UUID,
            ruleId: String,
            actor: String,
            provenance: ProspectProvenance,
            scope: TenantScope,
        ): Int? {
            val latest = defined.filter { it.first.ruleId == ruleId }.maxByOrNull { it.first.version } ?: return null
            if (!latest.second) return latest.first.version
            val version = latest.first.version + 1
            defined += latest.first.copy(version = version) to false
            return version
        }

        override fun activeRules(
            tenantId: UUID,
            scope: TenantScope,
        ): List<ScreeningRuleRow> =
            defined
                .groupBy { it.first.ruleId }
                .map { (_, versions) -> versions.maxBy { it.first.version } }
                .filter { it.second }
                .map { it.first }

        /** Drops a row in raw, as a hand-written insert would — criteria the edge never validated. */
        fun plant(row: ScreeningRuleRow) {
            defined += row to true
        }
    }
}
