import json

import httpx

from octo_agents.judge import (
    ChoiceQuestion,
    JudgeClient,
    NoulQuestion,
    ScoreQuestion,
)


def _client(handler) -> JudgeClient:
    transport = httpx.MockTransport(handler)
    return JudgeClient(
        endpoint="https://openrouter.ai/api/alpha/decisions",
        api_key="k",
        model="typesafe/jev-1.13",
        client=httpx.Client(transport=transport),
    )


def test_request_shape_matches_kotlin_contract() -> None:
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content)
        captured["auth"] = request.headers["authorization"]
        return httpx.Response(
            200,
            json={
                "model": "typesafe/jev-1.13",
                "provider": "typesafe",
                "id": "req-1",
                "answers": {
                    "advance": {"type": "noul", "noul": 0.82},
                    "why": {
                        "type": "choice",
                        "choice": "evidence",
                        "probabilities": {"evidence": 0.7, "gaps": 0.2, "risk": 0.1},
                        "confidence": 0.7,
                    },
                    "quality": {
                        "type": "score",
                        "score": 4.0,
                        "legend": {},
                        "probabilities": {},
                        "confidence": 0.9,
                    },
                },
                "usage": {"input_tokens": 10, "output_tokens": 4},
            },
        )

    client = _client(handler)
    result = client.decide(
        state={"prospect_id": "p-1"},
        questions={
            "advance": NoulQuestion(instructions="advance?"),
            "why": ChoiceQuestion(
                instructions="driver?",
                criteria={"evidence": "e", "gaps": "g", "risk": "r"},
            ),
            "quality": ScoreQuestion(
                instructions="rate", criteria=["1", "2", "3", "4", "5"]
            ),
        },
        session_id="s-1",
        user="octo-agents",
    )

    body = captured["body"]
    assert captured["auth"] == "Bearer k"
    assert body["model"] == "typesafe/jev-1.13"
    assert body["provider"] == {"zdr": True, "allow_fallbacks": False}
    assert body["session_id"] == "s-1"
    assert body["user"] == "octo-agents"
    assert body["questions"]["advance"]["type"] == "noul"
    assert body["questions"]["why"]["type"] == "choice"
    assert set(body["questions"]["why"]["criteria"]) == {"evidence", "gaps", "risk"}
    assert body["questions"]["quality"]["type"] == "score"
    assert result.answers["advance"]["noul"] == 0.82
    assert result.answers["why"]["choice"] == "evidence"
    assert result.model == "typesafe/jev-1.13"
