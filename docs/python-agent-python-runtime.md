# Python 编排实现与检查

第 4 项实现 Python 运行层，通过固定输入及模拟 HTTP 检查；第 5 项接通 Java 正式问诊，第 6 项清理旧 Java 类并交付，见 [交付说明](python-agent-delivery.md)。固定对照基线为原版提交 `5d322e39f756200cad4e09f08411c2b991a8cc44`。

## 模块和调用顺序

| Python 模块 | 职责 |
| --- | --- |
| `main.py`、`models.py` | 内部运行 HTTP、严格类型、身份/上下文检查、安全错误及取消 |
| `orchestrator.py` | 原分支顺序、提案提前返回、工具循环、证据拼接、引用和轨迹 |
| `planner.py` | 确定性工具选择、关键词顺序、参数及次数限制 |
| `memory.py`、`compat.py` | 历史过滤、Java 字符/空白语义、原 token 估算和 UTF-16 裁剪 |
| `safety.py`、`baseline_text.py` | 原急症/回答过滤规则、提示词、证据文案和 Mock 文案 |
| `providers.py` | Mock、Qwen 普通/工具调用、DeepSeek 普通对话适配 |
| `callbacks.py` | Java 工具、RAG 和提案 HTTP 回调；不直接访问业务数据源 |
| `settings.py`、`budget.py`、`errors.py` | 环境配置、共享运行预算和安全错误 |

编排沿用急症出口 → 开关/普通路径过期检查 → Java 提案 → 历史窗口 → 规则工具或模型工具循环 → Java RAG → 模型 → 证据回退/安全过滤 → 结构化结果的流程。模型工具调用由运行选项和供应商支持共同决定；DeepSeek 不增加工具能力。

提案解析、授权、草案持久化仍由 Java 完成，Python 不确认提案。RAG 的两域检索、合并、权限与引用来源也由 Java 保留。响应继续包含原 `content`、`runId`、`citations`、`actions`、`toolTraces`，外层 `outcome` 供后续 Java 指标适配使用。

## 兼容细节

- 固定输入对照使用原 Java 类执行结果，逐项比较规则选择、文字、Prompt、模型参数、历史、模型工具调用和结构化结果。工具执行耗时是非确定值，比较时只忽略 `elapsedMs`。
- 普通请求保留 `temperature=0.7`、`max_tokens=1024`，Qwen 工具请求保留 `temperature=0.2`、原 schema 和消息顺序。
- 历史使用 Java UTF-16 单元长度和原估算规则，覆盖中文、ASCII、补充平面字符和截断形成的未配对代理字符。发送模型前按 Java 编码行为处理代理字符。
- 模型工具调用 ID 缺失时沿用原补全格式；到达迭代末轮仍请求工具时直接结束，不能额外执行工具。
- 无证据拒答、患者事实问题、工具事实与 RAG 共存、模型误称无证据后的原文回退，均保留原条件。
- 保留 Mock 分支及文案，默认模拟延迟 1.5 秒；单元测试注入零延迟，避免等待真实服务。
- DeepSeek 保留原失败文本前缀和 HTTP 状态行为，底层连接异常详情改为安全提示。新增跨服务错误也只返回安全文案，不回显供应商正文、密钥或患者输入。

## 内部身份、预算和取消

`/internal/agent/v1/**` 在解析正文前检查 `X-Agent-Service-Token`。运行路由要求 `RunEnvelope`、`X-Agent-Context`、`X-Agent-Deadline-Epoch-Ms`；布尔值不会转成整数，超出 Java Long 范围、额外字段及错误枚举被拒绝。错误按 `InternalError` 返回，格式校验为 400。

Python 不持有 Java 签名密钥，只解析上下文作一致性检查并转发原 token。每次能力回调由 Java 验签、检查截止时间并复核患者授权。正常路径在任何模型调用前先调用 Java 提案接口；急症提前返回不访问患者能力或模型。服务凭据和上下文只发往固定配置的 Java 地址，不发给模型。

同一截止时间用于整次运行、每次回调和每次模型调用，单次请求不能重新获得完整预算。异步总预算还约束 HTTPX 的多个传输阶段；客户端断连取消编排，并在退出时停止、收集监控和工作子任务。运行及回调不自动重试，不跟随重定向，HTTPX 不使用环境代理。

服务创建与 `/health` 不建立外部连接。`APP_AI_*` 等配置从进程环境读取，配置方式见 [运行说明](../kangban-agent/README.md)。

## 检查依据

| 检查文件 | 覆盖 |
| --- | --- |
| 历史 Java `PythonMigrationParityTest.java` | 调用旧 Java 导出固定基线；第 6 项已删除导出器，方法与来源见 `tests/fixtures/README.md` |
| Python `tests/fixtures/java-baseline.json` | 只含合成数据的固定基线；不在 Python 运行时读取 Java 源码 |
| `test_java_parity.py` | 上述 80 项差分对照，含本机模拟模型请求捕获 |
| `test_run_api.py` | 30 项认证、严格类型、Java 回调、预算、取消、隐私和无重试检查 |
| `test_provider_errors.py` | 33 项模型 HTTP/解析/超时/连接/空值及 DeepSeek 失败行为 |
| `test_native_http.py` | 独立 Uvicorn 子进程与本机模拟 Java/模型的真实 TCP 检查、退出和端口释放 |
| `test_service_skeleton.py` | 15 项原有健康及 JSON Schema 示例检查 |
| `scripts/check-java-contract.py` | 第 3 项 Java 集成测试导出的 8 份响应，同时通过 JSON Schema 和实际 Python 严格类型 |

本阶段 Python 159 项全部通过；Java `mvn verify` 共 266 项，265 通过、1 项真实评测跳过，并完成打包。Java 测试强制使用 H2、Mock、本地 Embedding，关闭公共 MCP 和真实评测；Python 测试仅使用合成数据和模拟 HTTP。

静态 Java 基线已长期保留，依赖旧 Java 类的导出测试已删除；固定 Python 基线可独立执行。最终 Python 检查共 170 项，包含新增的 11 项本地启动检查。

这些检查证明已覆盖输入下的迁移行为和接口处理。第 5 项已完成 Java 正式问诊/SSE 的模拟接入验收；真实服务可用性、模型输出和跨服务性能尚未验证，需用户另行授权。
