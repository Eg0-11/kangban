"""One monotonic execution budget shared by all callback and provider requests."""

import time

import httpx

from .errors import AgentError, deadline_exceeded


class ExecutionBudget:
    def __init__(self, deadline_epoch_ms: int, maximum_ms: int = 170_000):
        remaining = deadline_epoch_ms / 1000 - time.time()
        if remaining <= 0:
            raise deadline_exceeded()
        if remaining * 1000 > maximum_ms:
            raise AgentError(400, "INVALID_DEADLINE", "内部 Agent 截止时间超出授权范围。")
        self.deadline_epoch_ms = deadline_epoch_ms
        self._monotonic_deadline = time.monotonic() + remaining

    def remaining(self) -> float:
        remaining = min(self._monotonic_deadline - time.monotonic(), self.deadline_epoch_ms / 1000 - time.time())
        if remaining <= 0:
            raise deadline_exceeded()
        return remaining

    def timeout(self, connect_ms: int, read_ms: int) -> httpx.Timeout:
        remaining = self.remaining()
        return httpx.Timeout(min(read_ms / 1000, remaining), connect=min(connect_ms / 1000, remaining))
