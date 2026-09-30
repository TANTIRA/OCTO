"""api_client hardening (backlog #345) — path segments are percent-encoded,
query strings are assembled by httpx (never f-string interpolation), and error
responses keep their status + body for the caller."""

from typing import Any

import httpx
import pytest

from octo_agents.api_client import OctoApiClient, OctoApiError


def make_client(handler: Any) -> OctoApiClient:
    return OctoApiClient(
        "http://api.test:8080",
        "tok",
        client=httpx.Client(transport=httpx.MockTransport(handler)),
    )


def recording(handler_body: Any = None) -> tuple[list[httpx.Request], OctoApiClient]:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(
            200, json=handler_body if handler_body is not None else {}
        )

    return seen, make_client(handler)


def test_bearer_header_and_empty_token_guard() -> None:
    seen, api = recording()
    api.get_agent_context("t-1")
    assert seen[0].headers["authorization"] == "Bearer tok"
    with pytest.raises(ValueError):
        OctoApiClient("http://api.test:8080", "")


def test_path_segments_are_percent_encoded() -> None:
    seen, api = recording()
    api.get_prospect("p/../x?stage=y")
    # raw_path keeps the wire encoding — url.path decodes it.
    assert seen[0].url.raw_path == b"/api/v1/prospects/p%2F..%2Fx%3Fstage%3Dy"

    api.list_agent_runs("t-1", subject_id="a/b?c")
    assert seen[1].url.params["subjectId"] == "a/b?c"


def test_query_params_encode_reserved_characters() -> None:
    seen, api = recording()
    api.list_pipeline("t&enant", "ic-review", limit=200, offset=50)
    params = seen[0].url.params
    assert params["tenantId"] == "t&enant"
    assert params["stage"] == "ic-review"
    assert params["limit"] == "200"
    assert params["offset"] == "50"


def test_get_dataset_passes_arbitrary_params_through_httpx() -> None:
    seen, api = recording()
    api.get_dataset("ds/1", as_of="2026-06-30", field="a&b")
    assert seen[0].url.raw_path == b"/api/v1/data/ds%2F1?as_of=2026-06-30&field=a%26b"
    assert seen[0].url.params["field"] == "a&b"


def test_error_keeps_status_and_body() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(422, text='{"error":"bad shape"}')

    with pytest.raises(OctoApiError) as ei:
        make_client(handler).get_prospect("p-1")
    assert ei.value.status_code == 422
    assert ei.value.body == '{"error":"bad shape"}'


def test_transient_5xx_retries_then_succeeds() -> None:
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(503 if calls == 1 else 200, json={"ok": True})

    assert make_client(handler).get_agent_context("t-1") == {"ok": True}
    assert calls == 2
