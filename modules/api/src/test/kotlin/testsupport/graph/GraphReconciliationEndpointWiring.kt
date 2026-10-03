package testsupport.graph

import com.octo.api.access.PlatformAdmin
import com.octo.api.graph.GraphDiscrepancy
import com.octo.api.graph.GraphDiscrepancyKind
import com.octo.api.graph.GraphReconciliation
import com.octo.api.graph.GraphReconciliationController
import com.octo.api.graph.GraphReconciliationRunner
import com.octo.api.graph.GraphTaskOpener
import com.octo.workflow.Task
import com.octo.workflow.opened
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * Test wiring for `GraphReconciliationEndpointTest`: a controller backed by a planted runner so the
 * endpoint can be exercised without `NEO4J_URI` (which keeps the real controller out of the scan).
 * The test reads the generated ids and the opened tasks from this same instance. This class
 * deliberately lives outside `com.octo.api` — OctoApplication's component scan reaches every
 * package under it, so any `@Configuration` kept there would leak into unrelated test contexts (a
 * planted `DataSource` once flipped every context's `db` health contributor DOWN).
 */
@Configuration(proxyBeanMethods = false)
class GraphReconciliationEndpointWiring {
    val tenantId: UUID = UUID.randomUUID()
    val octoId: UUID = UUID.randomUUID()
    val platformAdmin: UUID = UUID.randomUUID()
    val opened = mutableListOf<Task>()

    private val controller =
        GraphReconciliationController(
            GraphReconciliationRunner(
                DriverManagerDataSource("jdbc:postgresql://unused"),
                {
                    GraphReconciliation(
                        tenantId,
                        checked = 4,
                        discrepancies =
                            listOf(GraphDiscrepancy(GraphDiscrepancyKind.MISSING, "asset", octoId, "no node")),
                    )
                },
                GraphTaskOpener { task, _ ->
                    opened += task
                    opened(task)
                },
                null,
            ),
            PlatformAdmin(platformAdmin.toString()),
        )

    @Bean
    fun graphReconciliationTestController(): GraphReconciliationController = controller
}
