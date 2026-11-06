# Python Agent 首版迁移契约

状态：六阶段完成，旧 Java 编排及问诊专属客户端已从迁移版删除；Python 编排、Java 能力回调、原 REST/SSE 链路、启动/部署/CI 配套均完成本地模拟验收。真实服务联调与服务器部署不在本次已授权范围，详见交付说明和进度记录。

## 1. 基线、授权与交付边界

- 基线提交：`5d322e39f756200cad4e09f08411c2b991a8cc44`。
- 基线标签：`java-agent-before-python-2026-10-28`。
- 修改前工作目录：`kangban-java-original`。
- 迁移工作目录：`kangban-python-migration`。
- 本地迁移历史保存在 `codex/python-agent-migration`。GitHub 的 `main` 提供当前 Python 版，`codex/java-original` 保留 Java 原版。
- 用户已选择：直接用 Python 复现现有编排；最终从迁移版删除旧 Java 编排；保留独立原版和离线 Git 备份。
- 用户已选择：先用模拟模型、模拟工具/MCP、固定数据或测试数据库验证；使用真实 Qwen、Embedding、MySQL 或其他已有服务配置前另行确认。
- 每项任务完成后自检、报告进度，取得用户明确同意后才能开始下一项。
- 首版本地运行以 Windows 为准，保留现有服务的部署配置，并为新增 Python 服务补充部署说明；本次不执行服务器部署。
- 第 1 项仅增加设计、数据契约和进度文档。第 2 项准备隔离环境及健康检查骨架；内部业务接口和旧编排清理按后续批准点推进。

## 2. 职责与文件处理

| 现有位置 | 首版目标 | 最终处理 |
| --- | --- | --- |
| `agent/AgentOrchestrator.java` | Python 编排入口，保持现有分支顺序 | 验收后删除旧 Java 编排 |
| `agent/AgentToolPlanner.java` | Python 确定性工具规划 | 验收后删除旧实现 |
| `agent/ConversationMemoryPolicy.java` | Python 历史窗口过滤、裁剪与估算 | 验收后删除旧实现 |
| `agent/MedicalSafetyPolicy.java` | Python 急症出口、Prompt 安全规则及回答过滤 | 验收后删除无调用的旧实现 |
| `client/AiConsultationClient.java` 及 Mock、Qwen、DeepSeek 问诊实现 | Python 供应商适配器，保留三种配置路径 | 验收后清理问诊专属旧实现及无用类型 |
| `service/ConsultationService.java` | 继续管理会话、患者快照、消息落库、并发生成限制和 SSE；通过调用接口访问 Python | 保留 Java，替换编排调用点 |
| `agent/AgentExecutionContext*`、`AgentContextSigner` | 由 Java 创建可信上下文、签发短期回调凭据 | 保留；接入实际跨服务验证 |
| `AgentTool*`、四个患者工具及区块读取器 | Python 选择工具，Java 校验并执行 | 保留 Java 工具执行边界 |
| `McpPublicMedical*`、`PublicMedicalMcp*`、独立 MCP 项目 | 复用现有公开目录工具及敏感内容过滤 | 保留 Java；Python 通过工具接口访问 |
| `rag/`、`RagProperties` | 复用公共/私有检索、合并排序、引用及正文权限回查 | 保留 Java；不迁移文档入库或索引同步 |
| `ActionProposalService`、`AgentActionPlanner` | 复用提案解析、缺字段提示、禁止剂量推断、草案落库和确认式写入 | 保留 Java 提案业务规则，不重新实现解析 |
| `AgentMetrics`、`AgentHealthIndicator`、`AgentTraceIdFilter` | 保留已有指标名称和关联 ID，调整远端健康与运行状态采集 | 保留并适配，避免重复计数 |
| `client/AiConfig`、OCR、Embedding 共享依赖 | 保留现有 OCR / Embedding 功能 | 按引用检查，禁止按 `client/` 或 `agent/` 目录整体删除 |
| 前端、公开 Controller、数据库迁移文件 | 保持现有页面、路径、字段和业务状态 | 不改功能、不新增业务表 |

这里的“删除旧 Java 编排”指移除旧的 Agent 主循环、工具规划、记忆裁剪及问诊模型客户端，不包括 Java 的业务能力。`AgentActionPlanner` 是现有提案服务内部的确定性业务解析器，本次随提案服务保留；Python 决定何时调用该服务并处理其结果，不接管确认或写入。

迁移验证期间可临时存在 Java/Python 实现切换。第 6 项通过验收后，迁移版只保留 Python 编排入口；删除临时 Java 编排切换，失败时返回原有错误事件，不自动退回已删除的 Java 编排。恢复原版依靠独立原版目录、基线标签和离线备份。

## 3. 调用顺序与行为约束

1. Java 仍从登录身份和当前会话构建患者范围、数据库快照及历史，创建 `runId` / `traceId`。
2. Java 使用固定配置地址调用 Python；Python 的回调地址也只读取服务配置，不接受前端、Prompt 或工具参数传入 URL。
3. Python 按现有顺序处理：急症出口 → Agent 开关与普通路径上下文过期检查 → Java 提案服务；提案已处理则直接返回。
4. Python 裁剪历史。`modelToolCallingEnabled=false` 时运行现有规则规划；开启且供应商支持时才走模型工具循环。
5. 规则规划路径调用 Java 只读工具并组装工具事实。模型路径保留每轮 assistant/tool 消息顺序、工具调用 ID 补全及迭代边界。
6. RAG 开启时调用 Java 检索接口。Java 继续按 `privateEnabled` 搜索两域，并调用原 `RagSearchResult.merge`；Python 沿用原证据提示、无命中判断和误拒答原文回退。
7. Python 调用对应供应商，运行原回答安全过滤，返回结构化答案、引用、提案及轨迹。
8. Java 核验返回的 `runId` 与当前运行匹配，按原逻辑先保存完整回复，再发送原 SSE 事件。

兼容细节：

- 普通文本模型请求保持现有 Prompt、消息顺序、`temperature=0.7`、`max_tokens=1024`；Qwen 工具请求保持 `temperature=0.2` 及原工具 schema。首版不更换模型。
- Mock 保留现有分支和演示文案；测试可注入无延迟适配器，不依赖真实模型。
- DeepSeek 保留普通对话及历史路径，不擅自增加模型工具能力；使用模拟 HTTP 检查请求与原有错误返回语义。异常正文须避免泄漏密钥和请求内容。
- 默认规则规划会选健康快照作为兜底；RAG 无证据判断必须照原分支条件实现，不能把所有“空检索”一律拒绝或一律放行。
- `maxIterations` 在模型路径限制模型轮次；达到末轮且仍要求调用工具时按原逻辑结束，不能多执行一轮工具。
- Java 的 UTF-16 字符长度/截断与 Python Unicode 字符不同；记忆迁移必须覆盖中文、ASCII、空白及补充平面字符，保留现有估算和裁剪语义。
- `patientContextJson` 和工具 `content` 继续传 JSON 字符串，禁止擅自改成对象或重复编码。Java `Long` 对应 Python 严格整数，不经过浮点转换。
- 可空字段保留 `null`；`citations`、`actions`、`toolTraces` 使用空数组。提案未处理时 `content=null`、`action=null` 是合法结果。

## 4. 内部 HTTP 契约 v1

机器可读定义：[python-agent-v1.schema.json](contracts/python-agent-v1.schema.json)。它是 JSON Schema 和 HTTP 路由索引，不是已经运行的 OpenAPI 服务。

| 方向 | 方法与路径 | 请求定义 → 成功响应定义 |
| --- | --- | --- |
| Java → Python | `POST /internal/agent/v1/run` | `RunEnvelope` → `RunResultEnvelope` |
| 本机运维 → Python | `GET /health` | 无正文 → `HealthResponse`，不触发模型/数据库/MCP 调用 |
| Python → Java | `GET /internal/agent/v1/tools` | 无正文 → `ToolDefinitionsResponse` |
| Python → Java | `POST /internal/agent/v1/tools/execute` | `AgentToolCall` → `ToolExecutionResponse` |
| Python → Java | `POST /internal/agent/v1/rag/search` | `RagSearchRequest` → `RagSearchResult` |
| Python → Java | `POST /internal/agent/v1/actions/propose` | `ProposalRequest` → `ProposalDecision` |

请求统一使用 UTF-8 JSON。成功正文是表中对象，不额外套公开 API 的 `Result` 包装。`RunEnvelope` 仅包含 `contractVersion`、原 `AgentRequest` 和运行选项；Python 返回的 `response` 与原 `AgentResponse` 字段一致，外层 `outcome` 仅供 Java 指标适配，不传给前端。

运行选项由 Java 当前配置构造，包含 `enabled`、`modelToolCallingEnabled`、`ragEnabled`、`maxIterations`、三个历史窗口参数。`privateEnabled`、RAG 排序参数由 Java 检索服务继续读取。Python 模型适配读取现有 `APP_AI_*` 配置名，不把模型密钥放进运行请求。

### 4.1 服务身份与患者范围

- 所有 `/internal/agent/v1/**` 接口要求独立的 `X-Agent-Service-Token`；缺失配置时拒绝启动服务间调用，禁止内置通用默认密钥。
- Java 内部能力以 `APP_AGENT_INTERNAL_ENABLED=false` 默认关闭；启用时服务凭据和 Java 签名密钥必须不同。只开放表中的 Java 回调方法/路径，其他内部方法或路径返回 403 的 `InternalError`。
- Java → Python 的运行请求同时携带 `X-Agent-Context`，值为 Java 现有 `AgentContextSigner` 签发的短期 token。
- Java → Python 的运行请求携带 `X-Agent-Deadline-Epoch-Ms`（十进制 Unix 毫秒截止时间）；Python 向 Java 回调时原样传递，并限制单次 HTTP 超时不超过剩余预算。截止时间来自 Java 配置，不能从模型或前端输入取得。
- 上下文签名密钥只交由 Java；Python 将 token 当作不透明凭据转发，不签发新上下文。Python 的运行正文来自已认证 Java 服务，只把 `request.context` 用于编排，不允许工具参数覆盖身份。
- Python → Java 的每次回调携带两个上述头；Java 验签、检查过期，并根据签名上下文复核会话所有者、当前患者和家庭授权。患者身份从已验证 token 读取，回调正文不含可覆盖的用户/患者/会话字段。
- 普通用户 JWT 不能单独访问内部接口。使用独立的内部安全链处理精确路径，不将内部接口简单加入公开 `permitAll`。
- 签名凭据、服务密钥、问题、病历、模型请求正文均不进入日志；保留 `runId`、`traceId`、工具名、错误码和耗时。
- 内部提案接口只创建待确认草案。确认、取消、查询仍走原公开接口与登录认证；不向 Python 暴露通用写库或直接确认工具。

### 4.2 结果与错误

- 工具本身的 `SUCCESS` / `BLOCKED` / `FAILED` 继续返回 HTTP 200 的 `AgentToolResult`，由 Python 按原规则转换为最终结果或安全错误；服务认证错误使用非 200。
- 提案缺字段提示是 `handled=true, action=null` 的正常成功结果，不转成异常。`handled=false` 时继续问诊。
- 空检索是 HTTP 200、`hits=[]`；检索依赖故障返回 503，不能静默改成无依据回答。
- Java RAG 关闭时，直接调用内部检索接口返回 503 `RAG_DISABLED`；正常编排仍按原 RAG 开关决定是否发起检索。
- 内部非 200 响应遵循 `InternalError`：`contractVersion`、`code`、安全的 `message`、可空 `runId`、`retryable`。
- 建议状态码：400 格式/版本错误；401 服务身份无效；403 上下文或权限无效；410 上下文过期；503 依赖不可用；504 超时；500 未预期错误。格式校验统一为 400，避免 FastAPI 默认 422 穿透 Java。
- Java 将内部失败适配到现有 `ai_error` 文本事件；不把内部响应对象、堆栈或供应商敏感错误正文直接展示。
- 保留现有“无依据”“患者无权访问”“公共目录不可用”“调用次数上限”等用户提示；新服务不可达沿用通用 AI 不可用提示。

### 4.3 超时、重放与指标

- 外部 SSE 默认超时仍为 180 秒。Java → Python 总调用预算应留出落库/发事件余量（默认最多 170 秒），并通过请求截止时间或断连取消传递到内部执行；各轮模型/回调使用剩余预算，不能每轮重新获得完整总预算。
- HTTP 客户端首版不自动重试运行或提案请求，也不在失败后自动切换 Java 编排，避免不确定结果下重复创建草案。
- 保留已完成消息按 `messageId` 重放、客户端消息 ID 去重、进行中消息拦截，以及提案确认状态机的幂等。首版不宣称未完成运行或草案创建具备新增的跨进程 exactly-once 保证。
- 工具返回实际执行耗时，Python 生成原结构的工具轨迹；Java 保留原有工具/RAG 指标及运行指标名称，注意只计数一次。
- `success`、`safety_emergency`、`action_handled` 是内部结果分类；原实现提案提前返回不记录普通 Agent 成功计数，迁移时明确保留此统计口径。失败工具仍需记录，即使最终运行没有成功响应。

## 5. 对外 REST / SSE 保持一致

`ConsultationController` 的全部方法和路径保留，包括会话列表/创建、消息读取/发送、摘要刷新、流式回复、历史、患者资料更新、提案查询/确认/取消。前端仅继续访问 Java。

成功生成的事件顺序：

```text
thinking
agent_tool × N
thinking_done
action_proposal × N
citation × N
token（完整答案）
done（完整答案）
```

已完成回复重放沿用原逻辑：不重新调用编排；从 `agent_tool` 开始，允许 N 为 0。失败使用 `ai_error` 并结束。首版保留 7 个事件名：`thinking`、`thinking_done`、`agent_tool`、`action_proposal`、`citation`、`token`、`done`，另保留错误事件 `ai_error`（共 8 个事件名，其中成功路径 7 个）。

`agent_tool` 对外仍仅展示 `toolName`、`status`、`iteration`；内部 `elapsedMs`、`errorCode` 继续可保存，但不擅自增加前端展示字段。首版不改成逐 token 流、不新增前端页面。

## 6. 行为验收清单

下列清单在第 1 项建立，第 3～6 项逐步执行。各项实际运行结果以进度记录为准；源码映射不等同于全流程验收通过。

| 编号 | 场景与必须保持的结果 | 基线源码 / 已有测试 |
| --- | --- | --- |
| B01 | 急症关键词提前返回原提示，不调用提案、工具或模型 | `MedicalSafetyPolicyTest`、`AgentOrchestrator.run` |
| B02 | Agent 关闭及普通路径上下文过期时不调用模型 | `AgentOrchestratorTest.rejectsExpiredContextBeforeCallingProvider` |
| B03 | 默认规则选工具；健康指标/用药/病历/快照筛选与次数限制一致 | `AgentToolPlannerTest` |
| B04 | 规则路径保留工具事实，RAG 开启也不覆盖工具上下文 | `AgentOrchestratorTest.keepsToolContextWhenRagIsAlsoEnabled` |
| B05 | 模型工具开关默认 false；支持工具的供应商也不自动启用 | `AgentOrchestratorTest.defaultsToDeterministicServerSideToolPlanningWhenProviderSupportsToolCalling` |
| B06 | 模型自主选白名单工具，assistant/tool 顺序、历史和调用 ID 正确 | `QwenToolCallingContractTest`、`AgentOrchestratorTest` |
| B07 | 未知/写工具、越权、工具异常、中途失败、迭代上限均按原规则停止 | `AgentToolExecutorTest`、`AgentOrchestratorTest` |
| B08 | 公共及私有 RAG、三种向量路由、MySQL 正文/权限回查保持 | `rag/*Test`，原检索实现保留 |
| B09 | 无证据时按原条件拒答；患者数据问题及有工具事实路径保持 | `AgentOrchestratorTest` 的三类无证据场景 |
| B10 | 有真实证据而模型误拒答时返回检索原文；引用编号和 scope 一致 | `AgentOrchestratorTest.exposesRetrievedEvidenceWhenModelIncorrectlyClaimsNoEvidence` |
| B11 | 历史只来自当前会话；保留 user/assistant，工具结果不进跨轮记忆 | `ConsultationServiceTest`、`ConversationMemoryPolicyTest` |
| B12 | 历史消息数、字符与估算 token 裁剪保持，覆盖 Unicode 边界 | `ConversationMemoryPolicyTest` + 后续补充对照用例 |
| B13 | Qwen 普通/工具请求 Prompt、参数和历史正确；错误安全映射 | `QwenAiConsultationClientTest`、`QwenToolCallingContractTest` |
| B14 | Mock 演示分支/文案、DeepSeek 普通历史路径保留；测试不发真实请求 | 两个现有供应商实现 + 后续模拟 HTTP 用例 |
| B15 | 四个患者工具及四个公共 MCP 工具名称、参数和数据域保持 | `PatientHealth*Test`、`McpPublicMedical*Test` |
| B16 | 公共 MCP 参数不含患者健康内容；未启用、空结果和连接故障可区分 | `McpPublicMedicalAgentToolTest`、`McpPublicMedicalClientTest` |
| B17 | 提案缺关键字段时追问，不猜患者身份、剂量、日期或时间 | `AgentActionPlannerTest` |
| B18 | 完整健康/用药输入只生成待确认草案，Python 不能确认或写业务表 | `AgentActionPlannerTest`、`ActionProposalServiceTest` |
| B19 | 确认时检查登录态、会话、家庭权限、hash、过期及状态；重复确认不重复写入 | `ActionProposalService`、`ActionProposalServiceTest` |
| B20 | 新会话个性化摘要、摘要刷新与重复摘要去重保持 | `ConsultationServiceTest` |
| B21 | 客户端消息 ID 去重、回复精确关联原消息、已完成回复重放保持 | `ConsultationServiceTest` |
| B22 | SSE 成功顺序、数组/空值、轨迹展示字段及失败事件保持 | `ConsultationService`、前端 `ConsultationPage` + 后续集成用例 |
| B23 | 新内部接口拒绝缺失/错误服务凭据、篡改/过期 token、会话范围不符及权限撤销 | `AgentContextSignerTest` + 后续内部接口测试 |
| B24 | Python 不可达/超时/坏 JSON/版本或 runId 不符时安全失败，无隐式重试 | 后续 Java 调用适配测试 |
| B25 | 确诊、处方、剂量调整回答按原规则过滤，资料正文不当指令不进入工具执行 | `MedicalSafetyPolicyTest`、原 Prompt + 后续对照用例 |
| B26 | 原指标名称、健康检查、runId/traceId 关联保留；不记录病历或密钥 | `AgentMetricsTest`、`AgentTraceIdFilterTest` + 后续适配测试 |
| B27 | 删除旧编排后 Java 仍可构建，OCR/Embedding 共享配置不被误删 | 第 6 项依赖检索、Java 构建与针对性测试 |
| B28 | 原版干净、标签可解析、离线备份可校验，迁移版有独立提交 | 各阶段 Git 检查 |

固定数据对照比较工具调用、分支、Prompt、引用、提案和错误，不用真实模型逐字回答作为一致性标准。模型/数据库/MCP 的真实网络表现不由模拟验收替代，真实联调授权另行取得。

## 7. 后续阶段与批准点

| 阶段 | 内容 | 进入条件 |
| --- | --- | --- |
| 1 | 本文、机器可读契约和基线自检 | 用户已授权开始；完成后等待确认 |
| 2 | 独立 Python 环境、服务骨架、依赖固定及健康检查 | 用户确认第 1 项后 |
| 3 | Java 内部能力接口、服务身份/上下文校验及测试 | 用户确认第 2 项后 |
| 4 | Python 编排与三种供应商适配、固定数据对照 | 用户确认第 3 项后 |
| 5 | Java 接入 Python、模拟全流程回归 | 用户确认第 4 项后；真实联调需额外授权 |
| 6 | 清理旧编排、配置/文档/CI、最终构建及版本标记 | 用户确认第 5 项后 |

每项报告：交付文件、检查方法与结果、遗留问题、已完成阶段数和下一阶段范围。未经同意不进入下一阶段。
