"""Checkpoint 2: independent liveness and executable contract examples."""

import asyncio
import json
import socket
from pathlib import Path

import httpx
import pytest
from jsonschema import Draft202012Validator

from kangban_agent.main import create_app

CONTRACT_PATH = (
    Path(__file__).resolve().parents[2]
    / "docs"
    / "contracts"
    / "python-agent-v1.schema.json"
)
CONTRACT = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
EXAMPLES = [
    pytest.param(name, example, id=f"{name}-{index}")
    for name, definition in CONTRACT["$defs"].items()
    for index, example in enumerate(definition.get("examples", []), start=1)
]


def test_health_needs_no_credentials_or_external_http(monkeypatch, caplog):
    def forbidden_network(*args, **kwargs):
        raise AssertionError("The health check must not open external connections")

    monkeypatch.setattr(socket, "create_connection", forbidden_network)
    monkeypatch.setattr(httpx.HTTPTransport, "handle_request", forbidden_network)
    monkeypatch.setattr(httpx.AsyncHTTPTransport, "handle_async_request", forbidden_network)
    monkeypatch.setenv("APP_AI_PROVIDER", "qwen")
    monkeypatch.setenv("APP_AI_API_KEY", "test-canary-do-not-expose")
    monkeypatch.setenv("APP_MCP_PUBLIC_ENABLED", "true")

    async def request_health():
        transport = httpx.ASGITransport(app=create_app())
        async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
            return await client.get("/health")

    response = asyncio.run(request_health())

    assert response.status_code == 200
    assert response.headers["content-type"] == "application/json"
    Draft202012Validator(CONTRACT["$defs"]["HealthResponse"]).validate(response.json())
    assert "test-canary-do-not-expose" not in response.text + caplog.text


def test_contract_is_valid_draft_2020_12_json_schema():
    Draft202012Validator.check_schema(CONTRACT)


@pytest.mark.parametrize("definition_name,example", EXAMPLES)
def test_contract_examples(definition_name, example):
    schema = {
        "$schema": CONTRACT["$schema"],
        "$defs": CONTRACT["$defs"],
        "$ref": f"#/$defs/{definition_name}",
    }
    Draft202012Validator(schema).validate(example)
