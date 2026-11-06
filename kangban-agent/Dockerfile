FROM python:3.12.14-slim
COPY --from=ghcr.io/astral-sh/uv:0.12.18 /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_PYTHON_DOWNLOADS=never PYTHONUNBUFFERED=1 PATH="/app/.venv/bin:$PATH"
COPY pyproject.toml uv.lock .python-version README.md ./
RUN uv sync --locked --no-dev --python /usr/local/bin/python
COPY src ./src
RUN useradd --uid 10001 --create-home agent
USER agent
EXPOSE 8093
HEALTHCHECK --interval=10s --timeout=3s --retries=3 CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8093/health', timeout=2).close()"
CMD ["python", "-m", "uvicorn", "kangban_agent.main:create_app", "--factory", "--app-dir", "src", "--host", "0.0.0.0", "--port", "8093", "--no-access-log"]
