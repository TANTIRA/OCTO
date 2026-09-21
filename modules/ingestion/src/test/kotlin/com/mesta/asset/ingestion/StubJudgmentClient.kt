package com.mesta.asset.ingestion

import com.mesta.asset.controlpanel.judgment.ClassifiedState
import com.mesta.asset.controlpanel.judgment.JudgmentClient
import com.mesta.asset.controlpanel.judgment.JudgmentQuestion
import com.mesta.asset.controlpanel.judgment.JudgmentResult

internal class StubJudgmentClient(
    private val result: JudgmentResult,
) : JudgmentClient {
    var lastState: ClassifiedState? = null
    var lastQuestions: Map<String, JudgmentQuestion> = emptyMap()

    override fun decide(
        state: ClassifiedState,
        questions: Map<String, JudgmentQuestion>,
    ): JudgmentResult {
        lastState = state
        lastQuestions = questions
        return result
    }
}
