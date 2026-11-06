# 康伴 Python Agent

Python 服务已实现现有 Agent 编排、规则规划、记忆裁剪、安全处理及 Mock / Qwen / DeepSeek 问诊适配。Java 正式问诊入口已接通 Python，旧 Java 编排已清理，模拟 REST/SSE 全流程检查通过。统一启动、部署、版本和验收见 [交付说明](../docs/python-agent-delivery.md)。

## 运行环境

- Python 3.12，当前验证并固定使用 `3.12.14`（见 `.python-version`）。
- 使用 [uv](https://docs.astral.sh/uv/getting-started/installation/) 管理独立 `.venv` 和 `uv.lock`。解释器与依赖无需加入系统 PATH，也不依赖 Codex 的缓存运行时。
- `pyproject.toml` 固定直接依赖版本；`uv.lock` 固定传递依赖及发行文件校验信息，并提交到 Git。
- `.venv`、本地配置、缓存和日志不提交到 Git。虚拟环境依赖基础解释器，移动项目或更换电脑时应重新初始化，不能直接复制 `.venv`。

## Windows 初始化

在 `kangban-agent` 目录打开 PowerShell：

```powershell
.\scripts\initialize.ps1
```

脚本优先使用 PATH 中的 uv，否则查找当前用户的 `.local\bin\uv.exe`。也可显式指定：

```powershell
.\scripts\initialize.ps1 -UvPath 'C:\path\to\uv.exe'
```

首次初始化需要访问 PyPI；如果没有已安装的 Python 3.12.14，uv 也可能下载该解释器。脚本将依赖安装到本项目 `.venv`，不激活全局环境、不修改系统 PATH、不开启 Java 或外部业务服务。`--locked` 会在项目与锁文件不一致时停止，避免静默更新版本。

如果本机策略阻止运行脚本，可仅对当前启动的 PowerShell 进程使用：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\initialize.ps1
```

无需修改用户或机器范围的执行策略。

## 启动、检查与停止

```powershell
.\scripts\start-agent.ps1
```

默认监听 `127.0.0.1:8093`，在前台运行。在另一个终端检查：

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:8093/health'
```

响应：

```json
{"status":"ok","contractVersion":"1"}
```

此响应只表示 Python HTTP 服务存活，不表示模型、Java、数据库、RAG 或 MCP 已就绪。健康检查不读取 `.env`、不初始化外部客户端、不需要密钥。

在启动服务的终端按 `Ctrl+C` 停止。端口冲突时可显式指定其他端口，不会自动终止占用端口的程序：

```powershell
.\scripts\start-agent.ps1 -ListenPort 18093
```

`POST /internal/agent/v1/run` 已注册，需要服务凭据、Java 签发的上下文和截止时间三个请求头。服务凭据未配置时返回 503；配置后，缺失或错误凭据返回 401。不要通过构造假上下文测试真实患者能力；本项目测试使用独立的合成身份和模拟后端。

`/docs`、`/redoc`、`/openapi.json` 保持关闭。默认关闭 HTTP 访问日志。健康成功只表示 Python 进程存活，完整问诊还需 Java、内部授权和所选供应商可用。

## 编排配置

服务在启动时读取进程环境变量，不自动加载项目或 Java 的 `.env`。迁移版问诊需要 Java 和 Python 同时启动，并配置各自的服务地址和相同的服务凭据；Java 端配置及完整链路说明见 [接入说明](../docs/python-agent-integration.md)。当前测试只使用合成凭据和模拟后端。模型地址、Java 地址来自固定服务配置，不接受请求正文中的地址。

| 环境变量 | 默认值与含义 |
| --- | --- |
| `APP_AGENT_SERVICE_TOKEN` | 空；Java/Python 之间的独立服务凭据，运行接口必须配置 |
| `APP_AGENT_JAVA_BASE_URL` | `http://127.0.0.1:8080`；Java 内部能力所在服务 |
| `APP_AGENT_INTERNAL_MAX_REQUEST_MS` | `170000`；总运行预算上限，毫秒 |
| `APP_AI_PROVIDER` | `mock`；可选 `mock`、`qwen`、`deepseek` |
| `APP_AI_API_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`；沿用 Java 默认值，切换 DeepSeek 时也需指定其地址 |
| `APP_AI_API_KEY` | 空；所选真实模型凭据，不进入运行请求或日志 |
| `APP_AI_AI_MODEL` | `qwen3.7-plus-2026-05-26`；名称及默认值沿用 Java，按供应商配置 |
| `APP_AI_CONNECT_TIMEOUT` | `10000`；单次连接超时，毫秒 |
| `APP_AI_READ_TIMEOUT` | `120000`；单次 HTTP 等待上限，毫秒；还受剩余总预算限制 |

Java 的 `APP_AGENT_CONTEXT_SECRET` 签名密钥只留在 Java，Python 不读取、不签发上下文。Python 比较 token 解码值与正文上下文，并在回调时原样转发；Java 每次验签并复核会话和患者权限。Java 内部接口还需要独立开启和配置，见 [内部能力接口说明](../docs/python-agent-internal-api.md)。

Agent 开关、模型工具调用开关、RAG 开关及历史窗口等运行选项由 Java 在 `RunEnvelope.options` 中传入。Python 不直接连接业务数据库、向量库或 MCP，也不确认提案或写入健康记录。

## 本阶段检查

```powershell
.\scripts\test-agent.ps1
```

最终共 170 项 Python 检查通过，包括健康与契约、80 项旧 Java 基线对照、内部认证与严格类型、预算与断连取消、模型故障和隐私、实际 Uvicorn TCP 进程，以及统一启动的配置与进程清理。全部使用合成数据、Mock 或本机模拟 HTTP，不调用真实模型、数据库或 MCP。检查依据和覆盖边界见 [Python 编排说明](../docs/python-agent-python-runtime.md)。

也可直接使用项目解释器，无需激活环境：

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```

## 其他系统

在同一目录可使用 `uv sync --locked` 安装环境，再执行：

```sh
uv run --locked python -m uvicorn kangban_agent.main:create_app --factory --app-dir src --host 127.0.0.1 --port 8093 --no-access-log
```

第 2 项在 Windows 实测，其他系统的运行命令供后续部署准备，尚未进行平台实测。

完整迁移边界见 [迁移方案](../docs/python-agent-migration-plan.md)，当前检查点见 [进度记录](../docs/python-agent-migration-progress.md)。

Java 内部接口测试完成后，可验证其合成响应与 Python 契约兼容：

```powershell
.\.venv\Scripts\python.exe .\scripts\check-java-contract.py
```

此检查只读取本地测试输出，验证 JSON Schema 和实际 Python 严格类型，不访问 Java 服务、真实模型或数据库。接口说明见 [Java 内部能力接口](../docs/python-agent-internal-api.md)。
