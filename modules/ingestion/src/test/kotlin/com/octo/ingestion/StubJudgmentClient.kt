package com.octo.ingestion

import com.octo.controlpanel.judgment.ClassifiedState
import com.octo.controlpanel.judgment.JudgmentClient
import com.octo.controlpanel.judgment.JudgmentQuestion
import com.octo.controlpanel.judgment.JudgmentResult

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
