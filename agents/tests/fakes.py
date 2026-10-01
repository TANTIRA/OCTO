"""Base for API fakes (#345). A fake that overrides a client method with a
loose signature (`**kwargs`) would accept a call the real `OctoApiClient`
rejects — a misspelled or missing argument passes the test and breaks in
production. Every override here first binds its arguments against the real
method's signature, so drift fails the test with the client's own TypeError."""

import functools
import inspect
from collections.abc import Callable
from typing import Any

from octo_agents.api_client import OctoApiClient


def _bound_to_client(name: str, fake: Callable[..., Any]) -> Callable[..., Any]:
    real = inspect.signature(getattr(OctoApiClient, name))

    @functools.wraps(fake)
    def checked(self: Any, *args: Any, **kwargs: Any) -> Any:
        real.bind(self, *args, **kwargs)
        return fake(self, *args, **kwargs)

    return checked


class StrictFake(OctoApiClient):
    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        for name, attr in list(vars(cls).items()):
            if name.startswith("_") or not callable(attr):
                continue
            if callable(getattr(OctoApiClient, name, None)):
                setattr(cls, name, _bound_to_client(name, attr))
