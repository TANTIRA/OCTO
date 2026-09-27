package com.octo.api.prospect

import com.octo.api.OctoApplication
import com.octo.api.access.TenantAccess
import com.octo.api.access.TenantDirectory
import com.octo.api.access.TenantRole
import com.octo.dealsourcing.Prospect
import com.octo.dealsourcing.ProspectEvent
import com.octo.dealsourcing.ProspectSource
import com.octo.dealsourcing.ProspectStage
import com.octo.dealsourcing.ProspectState
import com.octo.dealsourcing.TenantScope
import com.octo.dealsourcing.next
import com.octo.dealsourcing.persistence.ProspectProvenance
import com.octo.dealsourcing.persistence.ProspectStore
import com.octo.dealsourcing.registered
import com.octo.workflow.Task
import com.octo.workflow.TaskState
import com.octo.workflow.TaskStatus
import com.octo.workflow.opened
import com.octo.workflow.persistence.TaskProvenance
import org.junit.jupiter.api.Test
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

/**
 * `/api/v1/prospects` end to end with a stubbed store and directory: members of the right role register
 * and advance, every other caller — viewer, non-member, no token — is denied, and the store's transition
 * errors surface as 409.
 */
class ProspectEndpointTest {
    private val member = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val tenantId = UUID.randomUUID()
    private val store = FakeProspectStore()
    private val tasks = FakeIcTasks()

    private val contextRunner =
        WebApplicationContextRunner()
            .withUserConfiguration(OctoApplication::class.java)
            .withBean(
                TenantDirectory::class.java,
                Supplier {
                    TenantDirectory { id ->
                        when (id) {
                            member -> listOf(TenantAccess(tenantId, "acme", TenantRole.ANALYST))
                            viewer -> listOf(TenantAccess(tenantId, "acme", TenantRole.VIEWER))
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
            mvc
                .perform(get("/api/v1/prospects/$id"))
                .andExpect(status().isForbidden)
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
                ).andExpect(status().isAccepted)
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

    /** Replays through the real state machine so tests exercise production transition semantics. */
    private class FakeProspectStore : ProspectStore {
        val states = linkedMapOf<UUID, ProspectState>()

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

        override fun listAtStage(
            tenantId: UUID,
            stage: ProspectStage,
            scope: TenantScope,
        ): List<ProspectState> = states.values.filter { it.prospect.tenantId == tenantId && it.stage == stage }

        override fun append(
            prospectId: UUID,
            event: ProspectEvent,
            provenance: ProspectProvenance,
            scope: TenantScope,
        ): ProspectState {
            val current = states[prospectId] ?: throw NoSuchElementException("no prospect $prospectId")
            return current.next(event).also { states[prospectId] = it }
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
    }
}
