# Python Agent 迁移交付

2026-10-28，六个阶段完成。本版使用 Python 复现原有编排，Java 继续提供业务能力，前端继续访问原 Java REST/SSE 接口。

## 修改前后版本

| 版本 | 工作目录名 | 固定 Git 标签 |
| --- | --- | --- |
| Java 原版 | `kangban-java-original` | `java-agent-before-python-2026-10-28` |
| Python 迁移版 | `kangban-python-migration` | `python-agent-migrated-2026-10-28` |

原版基线为 `5d322e39f756200cad4e09f08411c2b991a8cc44`。原版目录保持干净，迁移在 `codex/python-agent-migration` 分支独立提交；两个工作目录共享 Git 对象历史，工作文件独立。仓库外的 `kangban-version-map.md` 记录本机绝对路径、最终提交和离线备份。

本地离线备份为 `kangban-java-before-python-2026-10-28.bundle` 和 `kangban-python-after-migration-2026-10-28.bundle`，只包含对应版本的 Git 历史和已提交源码。GitHub 的 `main` 提供当前 Python 版，包含迁移交付后的 Java 网关空闲连接修复。GitHub 分支 `codex/java-original` 保留 Java 原版，`codex/python-agent` 保留 Python 迁移发布分支。分阶段提交和版本标签保存在本地。

查看差异：

```text
git diff origin/codex/java-original origin/main --stat
```

## 迁移和保留范围

Python 现在负责规则规划、模型工具循环、历史窗口、安全规则、原 Prompt/证据组装，以及 Mock、Qwen、DeepSeek 问诊适配。

迁移版已删除 11 个旧 Java 实现：`AgentOrchestrator`、`AgentToolPlanner`、`ConversationMemoryPolicy`、`MedicalSafetyPolicy`、`AiConsultationClient`、三个问诊客户端，以及 `AiToolCall`、`AiToolMessage`、`AiToolResponse`。相应旧测试与 Java 基线导出器也删除，固定基线及 80 项 Python 差分检查保留。Java 边界测试和最终 JAR 检查用于防止旧编译产物残留。

Java 保留会话和消息落库、SSE、身份/家庭授权、上下文签名、四个患者工具、公共 MCP、RAG、提案解析/确认/业务写入、指标，以及 OCR/Embedding 所需的 `AiConfig`、`DirectProxySelector` 和共享工具定义。没有新增业务表或改变原数据库迁移文件。

迁移版仅走 Python 编排，没有旧 Java 编排切换或自动回退；跨服务调用不自动重试。恢复原版使用独立原版目录或固定标签，不在运行失败时混用两套编排。

## Windows 本地运行

本机已有独立的 uv 及 Python 3.12.14，项目使用 `kangban-agent/.venv`，没有修改系统 PATH。新机器需先安装 uv；初始化脚本会准备固定解释器和依赖。Java 17、Maven、Node.js 与现有 MySQL/Redis/MinIO 仍需可用。

在迁移版根目录：

```powershell
.\scripts\initialize-local.ps1
```

此命令初始化 Python、执行前端 `npm ci`、干净打包 Java，并在不存在时生成被 Git 忽略的 `kangban.local.env`。服务凭据、Java 签名密钥、JWT 与验证码密钥分别生成，不在终端展示；已有文件不覆盖。本次已生成该本地文件，数据库与 MinIO 的占位值需按实际情况填写。

编辑 `kangban.local.env` 的 MySQL、Redis、MinIO 设置，再执行：

```powershell
.\scripts\start-local.ps1
```

默认地址为前端 `http://127.0.0.1:5173`、Java `http://127.0.0.1:8080`、Python `http://127.0.0.1:8093`。脚本等待 Python、Java、前端逐一就绪，前台按 Ctrl+C 会清理本次启动的进程树；失败时同样收尾，不终止原来占用端口的程序。日志位于被忽略的 `kangban-agent/.runtime/local-*`。

只检查配置和构建产物，不启动服务：

```powershell
.\scripts\start-local.ps1 -Check
```

可用 `-ConfigurationPath` 指定配置文件，或用 `-JavaPort`、`-AgentPort`、`-WebPort` 改端口；本地服务地址和 CORS 会同步构造。配置文件只支持逐行 `NAME=value`、整行注释和成对引号，内容按字面读取，不执行命令或变量展开。

默认使用 Mock，RAG 和公共 MCP 关闭。启动脚本不安装、不启动 MySQL/Redis/MinIO，也不自动读取原版的本地配置。服务间凭据发给 Java/Python；签名密钥和数据库配置只发给 Java，前端不接收这些凭据。

## 其他启动方式

- Linux/macOS：`bash scripts/initialize-local.sh`，填写本地文件，再 `bash scripts/start-local.sh`。uv 不在 PATH 时设置 `UV_BIN`。本次在 Windows 验证，其他平台的脚本和 CI 尚未实际运行。
- 分别运行服务：Java/Python 各自注入环境变量，Python 用 `kangban-agent/scripts/start-agent.ps1`，Java 用原启动方式，前端用 `npm run dev`。见 [Python 运行说明](../kangban-agent/README.md) 和 [内部 HTTP 接入](python-agent-integration.md)。
- 服务凭据需要两端相同且非空；`APP_AGENT_CONTEXT_SECRET` 只留 Java、与服务凭据不同；Java 开启 `APP_AGENT_INTERNAL_ENABLED=true`。Java/Python 的默认互访地址分别为 8093 / 8080。
- Python `/health` 只表示进程存活；Java 的 `kangbanAgent` 健康项检查配置和 Python 存活。未配置或 Python 不可达为 DOWN，Agent 关闭为 OUT_OF_SERVICE；这不表示真实模型已经验证。

## Docker 与 systemd

`kangban-server/docker-compose.yml` 已加入独立 Python 服务及健康依赖。Python 只在容器网络暴露 8093，Java 使用 `http://agent:8093`，Python 回调 `http://app:8080`，不存在相互等待健康的循环。签名密钥和数据库凭据不注入 Python。

准备专用部署环境文件，填好 Java 所需基础设施、JWT、验证码及独立 Agent 凭据；若只验证模拟模式，设置 `APP_AI_PROVIDER=mock`、`APP_RAG_EMBEDDING_PROVIDER=local`、`APP_RAG_ENABLED=false`。在仓库根目录：

```text
docker compose --env-file <本地部署环境文件> -f kangban-server/docker-compose.yml config --quiet
docker compose --env-file <本地部署环境文件> -f kangban-server/docker-compose.yml up -d --build
```

Compose 仍管理既有基础设施卷；切换版本时保留原卷，不执行删除卷的命令。Python 镜像使用固定 Python 3.12.14、uv 0.12.18 和 `uv.lock`，按非 root 用户运行。

ECS/systemd 分别使用 `deploy/ecs/app.env.example`、`agent.env.example` 和两个 service 模板。把 Python 项目放到 `/opt/kangban/kangban-agent`，执行 `uv sync --locked --no-dev --python 3.12.14`；环境文件分别放 `/opt/kangban/kangban.env`、`/opt/kangban/kangban-agent.env`，然后按模板配置服务。示例 Java 端口为 8081，Python 回调地址也为 8081，Java 调用 Python 为 8093。

本机 Docker 引擎未运行：已验证 Compose 配置及两个固定基础镜像的远程 manifest，没有构建/运行容器、启动 systemd 或部署服务器。模板依据 [uv Docker 文档](https://docs.astral.sh/uv/guides/integration/docker/) 和 [Compose 启动顺序文档](https://docs.docker.com/compose/how-tos/startup-order/) 配置。

## 检查与限制

Windows 统一检查：`scripts/release-check.ps1`；Bash：`bash scripts/release-check.sh`。PowerShell 可使用 `-Offline -MavenRepository <已准备好的缓存路径>`；Bash 可传 Maven 参数，并通过 `UV_BIN` 指定 uv。离线模式要求缓存和前端依赖已准备好。

最终检查结果：

- Python 170 项通过，其中保留 80 项 Java 固定基线对照，新增 11 项启动配置/进程清理检查。
- Java `clean verify`：287 项，286 通过、1 项真实评测跳过；Spring Boot JAR 打包成功。比第 5 项减少 30 项旧实现测试，新增 11 项旧类缺席检查。
- 前端 66 项通过，生产构建成功；仅修复一个原有源码检查测试的 CRLF 兼容性，业务源码及依赖锁定版本未改。
- 实际 Java REST/SSE → Python → 带认证 Java 回调 → 本机模拟模型的 7 项联调通过；消息保存/重放、提案确认幂等、工具、引用、安全出口及错误路径通过。
- 8 份 Java 实际合成回调响应通过 JSON Schema 与 Python 严格类型；初始化/启动脚本语法、Windows 配置检查、模拟启动收尾及 Compose 配置通过。
- CI 配置包含固定 Python 初始化、Python 检查、Java/Python 联调和回调契约检查。上述数据是本地迁移验收结果，GitHub 发布分支的运行状态可在 Actions 查看。

全部服务测试使用合成数据、独立 H2、模拟模型/检索及关闭的公共 MCP。没有读取现有真实业务凭据进行联调，没有真实模型额度或真实数据库访问，也没有手工操作真实浏览器页面。模拟检查只能确认覆盖输入下的行为；真实服务输出、网络与性能需另行授权验证。

`npm audit` 对未改动的现有锁文件报告 4 项依赖问题（3 high、1 moderate，涉及 Vite、nanoid、postcss、source-map-js）。本次记录该结果，未把依赖升级混入功能迁移。

分阶段记录见 [迁移进度](python-agent-migration-progress.md)，契约见 [迁移方案](python-agent-migration-plan.md)。历史 Java 架构/测量记录保留并标注原版身份。
