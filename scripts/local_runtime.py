"""Local Java/Python/web supervisor. Reads data-only configuration; never executes env files."""

import argparse
import base64
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import ProxyHandler, build_opener

ROOT = Path(__file__).resolve().parents[1]
MODEL_VARIABLES = {
    "APP_AI_PROVIDER", "APP_AI_API_URL", "APP_AI_API_KEY", "APP_AI_AI_MODEL",
    "APP_AI_CONNECT_TIMEOUT", "APP_AI_READ_TIMEOUT", "APP_AGENT_SERVICE_TOKEN",
    "APP_AGENT_INTERNAL_MAX_REQUEST_MS",
}


def initialize_configuration(root: Path, destination: Path):
    if destination.exists():
        print(f"Existing configuration kept: {destination}")
        return
    template = (root / "kangban.local.env.example").read_text(encoding="utf-8")
    values = {
        "GENERATE_JWT_SECRET": base64.b64encode(secrets.token_bytes(64)).decode("ascii"),
        "GENERATE_CONTEXT_SECRET": secrets.token_urlsafe(48),
        "GENERATE_SERVICE_TOKEN": secrets.token_urlsafe(48),
        "GENERATE_CAPTCHA_SECRET": secrets.token_urlsafe(48),
    }
    for placeholder, value in values.items():
        template = template.replace(placeholder, value)
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
        stream.write(template)
    print(f"Local configuration created: {destination}")
    print("Fill in your local MySQL, Redis and MinIO settings before starting. Generated secrets are not printed.")


def load_configuration(path: Path) -> dict[str, str]:
    values = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        name, separator, value = line.partition("=")
        name, value = name.strip(), value.strip()
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", name) or name in values:
            raise ValueError(f"Invalid or duplicate configuration name on line {number}.")
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0]:
                raise ValueError(f"Unclosed configuration value on line {number}.")
            value = value[1:-1]
        if "\x00" in value:
            raise ValueError(f"Invalid configuration value on line {number}.")
        values[name] = value
    return values


def validate_configuration(values: dict[str, str]):
    required = ("KANGBAN_DB_PASSWORD", "KANGBAN_MINIO_ACCESS_KEY", "KANGBAN_MINIO_SECRET_KEY",
                "KANGBAN_JWT_SECRET", "KANGBAN_CAPTCHA_SECRET", "APP_AGENT_CONTEXT_SECRET", "APP_AGENT_SERVICE_TOKEN")
    for name in required:
        value = values.get(name, "")
        if not value.strip() or value.startswith(("YOUR_", "GENERATE_", "CHANGE_ME")):
            raise ValueError(f"Set {name} in the local configuration file.")
    if values["APP_AGENT_CONTEXT_SECRET"] == values["APP_AGENT_SERVICE_TOKEN"]:
        raise ValueError("The context signing secret and service credential must differ.")
    provider = values.get("APP_AI_PROVIDER", "mock").lower()
    if provider not in {"mock", "qwen", "deepseek"}:
        raise ValueError("APP_AI_PROVIDER must be mock, qwen or deepseek.")
    if provider != "mock" and (not values.get("APP_AI_API_KEY") or values["APP_AI_API_KEY"].startswith(("YOUR_", "CHANGE_ME"))):
        raise ValueError("Set APP_AI_API_KEY for the selected model provider.")


def clean_environment() -> dict[str, str]:
    return {key: value for key, value in os.environ.items()
            if not key.startswith(("APP_", "KANGBAN_", "SPRING_", "SERVER_", "VITE_"))}


def build_services(root: Path, values: dict[str, str], ports: tuple[int, int, int]):
    java_port, agent_port, web_port = ports
    java = shutil.which("java")
    node = shutil.which("node")
    jar = root / "kangban-server/target/kangban-server-1.0.0.jar"
    vite = root / "kangban-web/node_modules/vite/bin/vite.js"
    if not java or not node or not jar.is_file() or not vite.is_file():
        raise ValueError("Java, Node, server JAR or Vite is missing. Run scripts/initialize-local first.")
    base = clean_environment()
    java_env = base | values | {
        "SPRING_PROFILES_ACTIVE": "local", "SERVER_ADDRESS": "127.0.0.1", "SERVER_PORT": str(java_port),
        "APP_AGENT_INTERNAL_ENABLED": "true", "APP_AGENT_PYTHON_BASE_URL": f"http://127.0.0.1:{agent_port}",
        "APP_BASE_URL": f"http://127.0.0.1:{web_port}",
        "APP_CORS_ALLOWED_ORIGINS": f"http://127.0.0.1:{web_port},http://localhost:{web_port}",
    }
    python_env = base | {key: value for key, value in values.items() if key in MODEL_VARIABLES} | {
        "APP_AGENT_JAVA_BASE_URL": f"http://127.0.0.1:{java_port}", "PYTHONUNBUFFERED": "1",
    }
    web_env = base | {"VITE_API_BASE_URL": f"http://127.0.0.1:{java_port}"}
    return [
        ("agent", [sys.executable, "-m", "uvicorn", "kangban_agent.main:create_app", "--factory", "--app-dir", "src",
                   "--host", "127.0.0.1", "--port", str(agent_port), "--no-access-log"], root / "kangban-agent",
         python_env, f"http://127.0.0.1:{agent_port}/health"),
        ("server", [java, "-jar", str(jar)], root / "kangban-server", java_env, f"http://127.0.0.1:{java_port}/actuator/health"),
        ("web", [node, str(vite), "--host", "127.0.0.1", "--port", str(web_port), "--strictPort"], root / "kangban-web",
         web_env, f"http://127.0.0.1:{web_port}/"),
    ]


def require_free_ports(ports: tuple[int, int, int]):
    if len(set(ports)) != len(ports) or any(port < 1 or port > 65535 for port in ports):
        raise ValueError("Choose three different ports between 1 and 65535.")
    for port in ports:
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError:
                raise ValueError(f"Port {port} is occupied; choose another port.") from None


def wait_healthy(process: subprocess.Popen, url: str, deadline: float, name: str):
    http = build_opener(ProxyHandler({}))
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"{name} exited during startup; inspect its local log.")
        try:
            with http.open(url, timeout=1) as response:
                if response.status == 200:
                    return
        except (URLError, OSError, TimeoutError):
            pass
        time.sleep(0.1)
    raise RuntimeError(f"{name} did not become healthy; inspect its local log.")


def stop_owned_process(process: subprocess.Popen):
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill.exe", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       creationflags=subprocess.CREATE_NO_WINDOW, check=False)
    else:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        if os.name == "nt":
            process.kill()
        else:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        process.wait(timeout=5)


def start_stack(root: Path, services, ports: tuple[int, int, int]):
    require_free_ports(ports)
    logs = root / "kangban-agent/.runtime" / time.strftime("local-%Y%m%d-%H%M%S")
    logs.mkdir(parents=True, exist_ok=True)
    children, streams = [], []
    print(f"Logs: {logs}", flush=True)
    try:
        for name, command, directory, environment, url in services:
            stream = (logs / f"{name}.log").open("wb")
            streams.append(stream)
            flags = {"creationflags": subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
            child = subprocess.Popen(command, cwd=directory, env=environment, stdout=stream, stderr=subprocess.STDOUT, **flags)
            children.append((name, child))
            wait_healthy(child, url, time.monotonic() + 90, name)
            print(f"{name}: {url}", flush=True)
        print("All three services are ready. Press Ctrl+C to stop this stack.", flush=True)
        while True:
            for name, child in children:
                if child.poll() is not None:
                    raise RuntimeError(f"{name} exited; stopping this stack. Inspect its local log.")
            time.sleep(0.2)
    except KeyboardInterrupt:
        print("Stopping this stack...", flush=True)
    finally:
        for _, child in reversed(children):
            stop_owned_process(child)
        for stream in streams:
            stream.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("init", "start"))
    parser.add_argument("--config", type=Path, default=ROOT / "kangban.local.env")
    parser.add_argument("--java-port", type=int, default=8080)
    parser.add_argument("--agent-port", type=int, default=8093)
    parser.add_argument("--web-port", type=int, default=5173)
    parser.add_argument("--check", action="store_true", help="Validate configuration and installed build artifacts without starting services")
    args = parser.parse_args()
    try:
        if args.mode == "init":
            initialize_configuration(ROOT, args.config)
            return
        values = load_configuration(args.config)
        validate_configuration(values)
        ports = (args.java_port, args.agent_port, args.web_port)
        services = build_services(ROOT, values, ports)
        require_free_ports(ports)
        if args.check:
            print("Configuration and build artifacts validated; no services started.")
            return
        start_stack(ROOT, services, ports)
    except FileNotFoundError:
        parser.exit(1, "A required local file is missing. Run scripts/initialize-local first.\n")
    except (ValueError, RuntimeError, OSError) as error:
        # Only our controlled validation messages are displayed; never echo OS errors with paths/env values.
        message = str(error) if isinstance(error, (ValueError, RuntimeError)) else "A local process could not be started; inspect its local log."
        parser.exit(1, message + "\n")


if __name__ == "__main__":
    main()
