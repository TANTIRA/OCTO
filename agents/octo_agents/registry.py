"""Approved-model registry with fail-closed ZDR enforcement (ADR-0005).

A model is usable only when it is listed in models.yaml for the requested role.
A request for confidential data to a non-ZDR-approved model raises before any
network call is constructed — the request object never exists, so it can never
be sent.
"""

from dataclasses import dataclass
from pathlib import Path

import yaml


class UnapprovedModelError(PermissionError):
    """No approved model exists for the requested role."""


class ZdrViolationError(PermissionError):
    """The model resolved for this role is not approved for confidential data."""


@dataclass(frozen=True)
class ApprovedModel:
    model_id: str
    role: str
    zdr: bool


class ApprovedModelRegistry:
    def __init__(self, path: str | Path) -> None:
        self._path = Path(path)
        self._models = self._load(self._path)

    @staticmethod
    def _load(path: Path) -> dict[str, ApprovedModel]:
        raw = yaml.safe_load(path.read_text())
        models: dict[str, ApprovedModel] = {}
        for model_id, spec in (raw.get("models") or {}).items():
            role = str(spec["role"])
            if role in models:
                raise ValueError(f"registry has two {role} models; roles are singular")
            models[role] = ApprovedModel(
                model_id=model_id, role=role, zdr=bool(spec.get("zdr"))
            )
        return models

    @property
    def path(self) -> Path:
        return self._path

    def resolve(self, role: str, *, confidential: bool) -> ApprovedModel:
        model = self._models.get(role)
        if model is None:
            raise UnapprovedModelError(f"no approved model for role '{role}'")
        if confidential and not model.zdr:
            raise ZdrViolationError(
                f"{model.model_id} (role '{role}') is not ZDR-approved; refusing confidential payload"
            )
        return model
