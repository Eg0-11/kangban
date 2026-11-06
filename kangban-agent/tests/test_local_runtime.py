"""Local launcher configuration isolation and real owned-process cleanup, without business services."""

import _thread
import importlib.util
import os
from pathlib import Path
import socket
import sys
import threading
import time

import pytest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("local_runtime", ROOT / "scripts/local_runtime.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


def configuration():
    return {name: "synthetic-local-value" for name in (
        "KANGBAN_DB_PASSWORD", "KANGBAN_MINIO_ACCESS_KEY", "KANGBAN_MINIO_SECRET_KEY",
        "KANGBAN_JWT_SECRET", "KANGBAN_CAPTCHA_SECRET", "APP_AGENT_SERVICE_TOKEN")
    } | {"APP_AGENT_CONTEXT_SECRET": "synthetic-java-only-secret", "APP_AI_PROVIDER": "mock"}


def test_initialization_creates_distinct_secrets_and_never_overwrites_existing_file(tmp_path, capsys):
    destination = tmp_path / "local.env"
    runtime.initialize_configuration(ROOT, destination)
    values = runtime.load_configuration(destination)
    secrets = [values[key] for key in ("APP_AGENT_SERVICE_TOKEN", "APP_AGENT_CONTEXT_SECRET", "KANGBAN_JWT_SECRET", "KANGBAN_CAPTCHA_SECRET")]
    assert len(set(secrets)) == 4
    assert all(len(value) >= 48 for value in secrets)
    assert all(value not in capsys.readouterr().out for value in secrets)
    original = destination.read_bytes()
    runtime.initialize_configuration(ROOT, destination)
    assert destination.read_bytes() == original


def test_configuration_is_literal_data_and_preserves_equals_and_hash(tmp_path):
    destination = tmp_path / "literal.env"
    destination.write_text('# comment\nAPP_AI_API_KEY="$(exit 1)=literal#value"\n', encoding="utf-8")
    assert runtime.load_configuration(destination) == {"APP_AI_API_KEY": "$(exit 1)=literal#value"}


@pytest.mark.parametrize("content", ["APP_A=1\nAPP_A=2", "lowercase=1", "APP_A", "APP_A='unterminated"])
def test_bad_configuration_is_rejected_without_echoing_values(tmp_path, content):
    destination = tmp_path / "bad.env"
    destination.write_text(content, encoding="utf-8")
    with pytest.raises(ValueError, match="configuration"):
        runtime.load_configuration(destination)


def test_configuration_requires_independent_credentials_and_explicit_real_provider_key():
    values = configuration()
    runtime.validate_configuration(values)
    with pytest.raises(ValueError, match="must differ"):
        runtime.validate_configuration(values | {"APP_AGENT_CONTEXT_SECRET": values["APP_AGENT_SERVICE_TOKEN"]})
    with pytest.raises(ValueError, match="APP_AI_API_KEY"):
        runtime.validate_configuration(values | {"APP_AI_PROVIDER": "qwen"})
    with pytest.raises(ValueError, match="KANGBAN_DB_PASSWORD"):
        runtime.validate_configuration(values | {"KANGBAN_DB_PASSWORD": "YOUR_MYSQL_PASSWORD"})


def test_model_process_never_receives_java_signing_database_or_frontend_secrets(tmp_path, monkeypatch):
    monkeypatch.setenv("APP_AGENT_CONTEXT_SECRET", "inherited-java-canary")
    monkeypatch.setenv("KANGBAN_DB_PASSWORD", "inherited-db-canary")
    monkeypatch.setenv("VITE_API_BASE_URL", "inherited-invalid-url")
    monkeypatch.setattr(runtime.shutil, "which", lambda name: name)
    for name in ("kangban-server/target/kangban-server-1.0.0.jar", "kangban-web/node_modules/vite/bin/vite.js"):
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()
    services = runtime.build_services(tmp_path, configuration(), (18080, 18093, 15173))
    environments = {name: env for name, _, _, env, _ in services}
    assert "APP_AGENT_CONTEXT_SECRET" not in environments["agent"]
    assert "KANGBAN_DB_PASSWORD" not in environments["agent"]
    assert environments["agent"]["APP_AGENT_SERVICE_TOKEN"] == configuration()["APP_AGENT_SERVICE_TOKEN"]
    assert "APP_AGENT_SERVICE_TOKEN" not in environments["web"]
    assert "KANGBAN_DB_PASSWORD" not in environments["web"]
    assert environments["server"]["APP_AGENT_CONTEXT_SECRET"] == configuration()["APP_AGENT_CONTEXT_SECRET"]
    assert "15173" in environments["server"]["APP_CORS_ALLOWED_ORIGINS"]
    assert environments["web"]["VITE_API_BASE_URL"] == "http://127.0.0.1:18080"


def test_occupied_or_duplicate_ports_are_rejected_without_stopping_other_processes():
    with pytest.raises(ValueError, match="different ports"):
        runtime.require_free_ports((8080, 8080, 8093))
    with socket.socket() as occupied:
        occupied.bind(("127.0.0.1", 0))
        port = occupied.getsockname()[1]
        others = free_ports(2)
        with pytest.raises(ValueError, match="occupied"):
            runtime.require_free_ports((port, *others))
        assert occupied.getsockname()[1] == port


HTTP_STUB = """
from http.server import BaseHTTPRequestHandler, HTTPServer
import sys
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b'ok')
HTTPServer(('127.0.0.1',int(sys.argv[1])),Handler).serve_forever()
"""


def free_ports(count):
    sockets = [socket.socket() for _ in range(count)]
    try:
        for selected in sockets:
            selected.bind(("127.0.0.1", 0))
        return tuple(selected.getsockname()[1] for selected in sockets)
    finally:
        for selected in sockets:
            selected.close()


def assert_ports_released(ports):
    for port in ports:
        for _ in range(40):
            with socket.socket() as probe:
                probe.settimeout(.1)
                if probe.connect_ex(("127.0.0.1", port)) != 0:
                    break
            time.sleep(.05)
        else:
            pytest.fail("A supervised process left its port open")


def test_native_supervisor_ctrl_c_cleans_three_owned_http_processes(tmp_path, capsys):
    ports = free_ports(3)
    services = [(name, [sys.executable, "-c", HTTP_STUB, str(port)], tmp_path, runtime.clean_environment(),
                 f"http://127.0.0.1:{port}/") for name, port in zip(("agent", "server", "web"), ports)]
    timer = threading.Timer(3, _thread.interrupt_main)
    timer.start()
    try:
        runtime.start_stack(tmp_path, services, ports)
    finally:
        timer.cancel()
    output = capsys.readouterr().out
    assert "All three services are ready" in output
    assert "Stopping this stack" in output
    assert_ports_released(ports)


def test_native_supervisor_startup_failure_cleans_previously_started_service(tmp_path):
    ports = free_ports(3)
    services = [
        ("agent", [sys.executable, "-c", HTTP_STUB, str(ports[0])], tmp_path, runtime.clean_environment(), f"http://127.0.0.1:{ports[0]}/"),
        ("server", [sys.executable, "-c", "raise SystemExit(7)"], tmp_path, runtime.clean_environment(), f"http://127.0.0.1:{ports[1]}/"),
    ]
    with pytest.raises(RuntimeError, match="exited during startup"):
        runtime.start_stack(tmp_path, services, ports)
    assert_ports_released(ports)
