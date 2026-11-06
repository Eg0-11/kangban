"""Explicit environment settings; creating the app opens no network connections."""

import os
from dataclasses import dataclass, field
from urllib.parse import urlsplit

from .errors import AgentError


@dataclass(frozen=True)
class Settings:
    service_token: str = field(default="", repr=False)
    java_base_url: str = "http://127.0.0.1:8080"
    provider: str = "mock"
    api_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    api_key: str = field(default="", repr=False)
    model: str = "qwen3.7-plus-2026-05-26"
    connect_timeout_ms: int = 10_000
    read_timeout_ms: int = 120_000
    max_request_ms: int = 170_000

    @classmethod
    def from_env(cls):
        return cls(
            service_token=os.environ.get("APP_AGENT_SERVICE_TOKEN", ""),
            java_base_url=os.environ.get("APP_AGENT_JAVA_BASE_URL", "http://127.0.0.1:8080"),
            provider=os.environ.get("APP_AI_PROVIDER", "mock").lower(),
            api_url=os.environ.get("APP_AI_API_URL", cls.api_url),
            api_key=os.environ.get("APP_AI_API_KEY", ""),
            model=os.environ.get("APP_AI_AI_MODEL", cls.model),
            connect_timeout_ms=int(os.environ.get("APP_AI_CONNECT_TIMEOUT", "10000")),
            read_timeout_ms=int(os.environ.get("APP_AI_READ_TIMEOUT", "120000")),
            max_request_ms=int(os.environ.get("APP_AGENT_INTERNAL_MAX_REQUEST_MS", "170000")),
        )

    def validate_run_configuration(self):
        if self.provider not in {"mock", "qwen", "deepseek"} or min(
            self.connect_timeout_ms, self.read_timeout_ms, self.max_request_ms
        ) <= 0:
            raise AgentError(503, "INVALID_CONFIGURATION", "AI 服务配置无效，请联系管理员。")
        for url in (self.java_base_url, self.api_url):
            parsed = urlsplit(url)
            if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
                raise AgentError(503, "INVALID_CONFIGURATION", "AI 服务地址配置无效，请联系管理员。")
        if urlsplit(self.java_base_url).query:
            raise AgentError(503, "INVALID_CONFIGURATION", "内部业务服务地址配置无效。")
