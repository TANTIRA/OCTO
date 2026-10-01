"""StrictFake must reject a call the real client would reject (#345)."""

from typing import Any

import pytest

from .fakes import StrictFake


class LooseApi(StrictFake):
    def __init__(self) -> None:
        pass

    def finish_run(self, run_id: str, **kwargs: Any) -> Any:
        return kwargs


def test_matching_call_passes_through() -> None:
    assert LooseApi().finish_run("run-1", status="completed") == {"status": "completed"}


def test_misspelled_argument_is_rejected() -> None:
    with pytest.raises(TypeError):
        LooseApi().finish_run("run-1", stauts="completed")


def test_missing_required_argument_is_rejected() -> None:
    with pytest.raises(TypeError):
        LooseApi().finish_run("run-1")
