from pathlib import Path

import pytest

from octo_agents.registry import (
    ApprovedModelRegistry,
    UnapprovedModelError,
    ZdrViolationError,
)

REGISTRY = Path(__file__).resolve().parent.parent / "models.yaml"


def test_judge_resolves_to_jev() -> None:
    registry = ApprovedModelRegistry(REGISTRY)
    model = registry.resolve("judge", confidential=True)
    assert model.model_id == "typesafe/jev-1.13"
    assert model.zdr is True


def test_drafter_resolves_to_deepseek() -> None:
    registry = ApprovedModelRegistry(REGISTRY)
    model = registry.resolve("drafter", confidential=True)
    assert model.model_id == "deepseek/deepseek-v4.1-flash"


def test_unknown_role_is_refused() -> None:
    registry = ApprovedModelRegistry(REGISTRY)
    with pytest.raises(UnapprovedModelError):
        registry.resolve("planner", confidential=False)


def test_non_zdr_model_is_refused_confidential_payloads(tmp_path: Path) -> None:
    registry_file = tmp_path / "models.yaml"
    registry_file.write_text(
        "models:\n  acme/plain-1:\n    role: judge\n    zdr: false\n"
    )
    registry = ApprovedModelRegistry(registry_file)
    with pytest.raises(ZdrViolationError):
        registry.resolve("judge", confidential=True)
    # The same model may still serve non-confidential calls.
    assert registry.resolve("judge", confidential=False).model_id == "acme/plain-1"
