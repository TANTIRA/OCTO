"""Judge client — jev via the OpenRouter decisions endpoint.

jev is a System-One decision model: it takes unstructured state plus typed
questions (noul/choice/score) and returns calibrated probabilities, not text.
The request/response shapes mirror modules/control-panel's Kotlin judgment
contract so both platforms read the same answers.
"""

from collections.abc import Mapping
from typing import Any, Literal

import httpx
from pydantic import BaseModel, Field


class NoulCriteria(BaseModel):
    when_true: str | None = Field(default=None, alias="true")
    when_false: str | None = Field(default=None, alias="false")

    model_config = {"populate_by_name": True}


class NoulQuestion(BaseModel):
    type: Literal["noul"] = "noul"
    instructions: str
    criteria: NoulCriteria | None = None


class ChoiceQuestion(BaseModel):
    type: Literal["choice"] = "choice"
    instructions: str
    criteria: dict[str, str | None]


class ScoreQuestion(BaseModel):
    type: Literal["score"] = "score"
    instructions: str
    criteria: list[str]


JudgmentQuestion = NoulQuestion | ChoiceQuestion | ScoreQuestion


class NoulAnswer(BaseModel):
    noul: float


class ChoiceAnswer(BaseModel):
    choice: str
    probabilities: dict[str, float]
    confidence: float


class ScoreAnswer(BaseModel):
    score: float
    legend: dict[str, str] = Field(default_factory=dict)
    probabilities: dict[str, float] = Field(default_factory=dict)
    confidence: float


class TokenUsage(BaseModel):
    input_tokens: int = 0
    output_tokens: int = 0
    cost: float | None = None


class JudgmentResult(BaseModel):
    model: str
    answers: dict[str, Any]
    usage: TokenUsage | None = None
    id: str | None = None
    provider: str | None = None


class JudgmentRequestError(RuntimeError):
    def __init__(self, status_code: int, body: str) -> None:
        super().__init__(f"decision request failed with status {status_code}")
        self.status_code = status_code


class JudgeClient:
    """Sends typed questions to the approved judge model. Confidential states
    only ever reach it after the registry's ZDR gate has passed."""

    def __init__(
        self,
        *,
        endpoint: str,
        api_key: str,
        model: str,
        timeout_s: float = 60.0,
        client: httpx.Client | None = None,
    ) -> None:
        self._endpoint = endpoint
        self._api_key = api_key
        self._model = model
        self._client = client or httpx.Client(timeout=timeout_s)

    def decide(
        self,
        state: Any,
        questions: Mapping[str, JudgmentQuestion],
        *,
        session_id: str | None = None,
        user: str | None = None,
    ) -> JudgmentResult:
        if not questions:
            raise ValueError("at least one question is required")
        payload = {
            "model": self._model,
            "state": state,
            "questions": {
                qid: q.model_dump(by_alias=True, exclude_none=True)
                for qid, q in questions.items()
            },
            "provider": {"allow_fallbacks": False},
        }
        if session_id is not None:
            payload["session_id"] = session_id
        if user is not None:
            payload["user"] = user

        response = self._client.post(
            self._endpoint,
            headers={
                "Authorization": f"Bearer {self._api_key}",
                "Content-Type": "application/json",
            },
            json=payload,
        )
        if response.status_code not in range(200, 300):
            raise JudgmentRequestError(response.status_code, response.text[:512])
        return JudgmentResult.model_validate(response.json())
