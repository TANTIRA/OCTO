package com.mesta.asset.ingestion.eval

import com.mesta.asset.controlpanel.judgment.DecisionModelConfig
import com.mesta.asset.controlpanel.judgment.JdkHttpTransport
import com.mesta.asset.controlpanel.judgment.OpenRouterDecisionsClient
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real decision model against the eval sets, with the thresholds AGENTS.md requires CI to enforce. It runs
 * only where OPENROUTER_API_KEY is set, such as CI with the secret configured; elsewhere it is reported as skipped.
 */
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class DecisionModelEvalTest {
    @Test
    fun `the decision model meets every eval threshold`() {
        val scores = DecisionEval.score(OpenRouterDecisionsClient(DecisionModelConfig.fromEnvironment(), JdkHttpTransport()))

        assertTrue(scores.all { it.meetsThreshold }, "below threshold: ${scores.filterNot { it.meetsThreshold }}; all: $scores")
    }
}
