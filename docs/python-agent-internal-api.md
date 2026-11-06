# Java 内部 Agent 能力接口（第 3 项）

Java 回调边界与 Python 编排已实现，第 5 项已接通 Java 问诊到 Python。旧 Java 编排类暂存但不再被问诊入口调用；当前运行方式见 [接入说明](python-agent-integration.md)。

## 启用配置

内部接口默认关闭。显式启用时使用以下环境变量，不把真实值写入仓库：

```text
APP_AGENT_INTERNAL_ENABLED=true
APP_AGENT_SERVICE_TOKEN=<独立的服务间凭据>
APP_AGENT_CONTEXT_SECRET=<仅由 Java 持有的独立签名密钥>
APP_AGENT_INTERNAL_MAX_REQUEST_MS=170000
```

两个凭据必须非空且不同；缺失或配置错误时拒绝启用。默认关闭时内部请求返回 503；迁移版问诊需要开启此边界并启动 Python，未配置时安全失败，不退回旧 Java 编排。RAG 接口还要求原 `APP_RAG_ENABLED=true`；私有资料是否检索继续由 `APP_RAG_PRIVATE_ENABLED` 和现有检索服务的权限判断决定。

上下文凭据只能由 Java 在已授权会话上创建和签发。不提供给前端或 Python 自行签发身份的 HTTP 接口；正常运行时由 `PythonAgentGateway` 签发并传递。

## 请求身份

每个请求要求三个头：

| 头 | 含义 |
| --- | --- |
| `X-Agent-Service-Token` | 独立服务间凭据，使用固定时间比较 |
| `X-Agent-Context` | Java 签发的短期上下文 token |
| `X-Agent-Deadline-Epoch-Ms` | 原运行的 Unix 毫秒截止时间 |

服务校验签名、有效期、截止时间预算、会话所有者、当前患者和成员、账号状态、当前家庭 AI 授权及成员归属。每次回调重新检查；服务凭据和签名有效不能跳过权限撤销检查。截止时间允许等于上下文到期时刻，但不得超出它或剩余调用预算；业务执行前后均检查有效性。

内部路径使用优先匹配的独立 Spring Security 安全链，仅允许下表的方法和路径。用户 JWT 不能替代服务身份，服务身份也不能用于访问公开业务接口。未知内部路径和不允许的方法返回 403；不向 Python 开放确认/取消动作或通用写入入口。

JWT 过滤器继续在公开业务安全链运行，关闭其额外的 Servlet 自动注册，避免它再次处理内部调用。该行为通过 MockMvc、真实本机 HTTP 和原有公开接口回归共同验证。

## 接口

请求/响应字段以 [v1 契约](contracts/python-agent-v1.schema.json) 为准。成功响应为原始 JSON，不套公开 API 的 `Result` 包装。

| 方法与路径 | 请求正文 | 成功响应 |
| --- | --- | --- |
| `GET /internal/agent/v1/tools` | 无 | 现有 8 个只读工具定义数组 |
| `POST /internal/agent/v1/tools/execute` | `{"name":"get_health_metrics","arguments":{"metric":"heart_rate","limit":20}}` | `{"result":AgentToolResult,"elapsedMs":整数}` |
| `POST /internal/agent/v1/rag/search` | `{"query":"用户问题"}` | 原 `RagSearchResult`：`context` 与 `hits` |
| `POST /internal/agent/v1/actions/propose` | `{"message":"用户原始输入"}` | 原提案决策：`handled`、可空 `content`、可空 `action` |

正文根字段严格按契约检查，拒绝额外身份字段、缺字段及错误类型。工具 `arguments` 中的任何身份值都不能替代签名上下文；现有工具继续按授权患者执行。工具白名单、只读规则、公共 MCP 的敏感参数过滤均复用原实现。

RAG 继续通过原路由分别查询公共与私有证据，调用原合并排序和引用组装。未命中是成功的空 `hits`；RAG 关闭为 503 `RAG_DISABLED`；检索依赖故障为 503，不静默生成无依据答案。

提案接口复用现有确定性解析：未识别录入意图时返回 `handled=false`；缺字段时返回追问；完整输入只创建待确认草案及审计，不执行健康记录或用药写入。公开确认接口保留原用户认证、hash/过期/权限检查和幂等行为。

## 错误与日志

认证及请求错误返回 `InternalError`：

```json
{"contractVersion":"1","code":"INVALID_CONTEXT","message":"内部 Agent 上下文无效。","runId":null,"retryable":false}
```

- 401：服务身份错误；403：上下文/患者范围无效或访问不允许的路径。
- 410：签名上下文过期；400：截止时间、JSON、根字段或类型无效。
- 504：调用截止时间已到；503：接口未启用、授权数据/检索依赖不可用。
- 工具执行的 `SUCCESS` / `BLOCKED` / `FAILED` 仍是 HTTP 200 的结构化结果。
- 错误不回显凭据、问题、病历、底层异常正文或堆栈。工具指标沿用原名称，未知工具使用受控的 `unknown_tool` 标签。

第 3 项建立回调截止时间检查，第 4、5 项已补充 Python 剩余预算、Java 整体 HTTP 超时和断连取消。请求超时不构成草案创建的 exactly-once 保证，客户端不得自动重试提案创建。

## 检查方式

Java 检查使用 `test` profile 的 H2 数据库、Mock 模型和模拟 RAG。新增集成测试显式固定独立 H2 URL，不读取已有 MySQL 配置，公共医疗 MCP 关闭，真实 Qwen 评测未启用。

在 `kangban-server` 目录单独执行新增边界检查：

```powershell
mvn --batch-mode test '-Dspring.profiles.active=test' '-Dtest=InternalAgentAccessGuardTest,InternalAgentIntegrationTest'
```

Java 内部接口测试会把合成响应导出到被 Git 忽略的 `kangban-server/target/internal-agent-contract-fixtures.json`。在 `kangban-agent` 目录可运行：

```powershell
.\.venv\Scripts\python.exe .\scripts\check-java-contract.py
```

它使用既定 JSON Schema 校验 Java 实际输出的工具定义、工具成功/失败、RAG 引用、提案三种分支及认证错误，避免只检查手写示例。

安全链设计参考 [Spring Security 6.5 多安全链文档](https://docs.spring.io/spring-security/reference/6.5/servlet/configuration/java.html#_multiple_httpsecurity_instances)。本次没有升级 Spring Boot / Spring Security 或新增 Maven 依赖。
