# 康伴医疗 Agent 架构图

## 总体架构

```mermaid
flowchart TB
    USER[用户 / 家庭成员]
    WEB[React + Vite
    首页 / 健康 / 用药 / 病历 / AI问诊]
    NGINX[Nginx
    生产同源代理]
    API[Spring Boot API
    Controller / Security]
    AUTH[JWT 身份与家庭授权]
    DOMAIN[领域服务
    健康 / 用药 / 病历 / 家庭]
    GATEWAY[Java PythonAgentGateway
    签名、HTTP、结果校验]
    AGENT[Python AgentOrchestrator
    规划、记忆、安全、模型]
    CALLBACK[Java 内部能力
    逐次授权、工具/RAG/提案]
    TOOLS[只读 Agent 工具
    健康指标 / 用药 / 病历]
    RAG[RoutingKnowledgeSearchService
    MySQL / Dual / Qdrant 路由]
    INGEST[KnowledgeDocumentService
    解析 / 切片 / Embedding / 审核发布]
    QWEN_EMB[Qwen text-embedding-v4]
    QWEN_CHAT[Qwen Chat / Tool Calling]
    MYSQL[(MySQL
    业务数据、文档元数据、切片、引用、审计)]
    QDRANT[(Qdrant
    可重建向量索引)]
    REDIS[(Redis
    验证码、缓存、短期状态)]
    MINIO[(MinIO
    头像、病历和对象文件)]

    USER --> WEB
    WEB -->|本地 REST / SSE| API
    WEB -->|生产同源| NGINX --> API
    API --> AUTH
    API --> DOMAIN
    DOMAIN --> MYSQL
    DOMAIN --> REDIS
    DOMAIN --> MINIO
    API --> GATEWAY -->|内部认证 HTTP| AGENT
    AGENT -->|不透明签名上下文| CALLBACK
    CALLBACK --> TOOLS --> DOMAIN
    CALLBACK --> RAG
    RAG -->|mysql-jdbc| MYSQL
    RAG -->|qdrant 召回 + 权限回查| QDRANT
    RAG --> MYSQL
    RAG --> QWEN_EMB
    AGENT --> QWEN_CHAT
    QWEN_CHAT --> AGENT
    AGENT -->|token / thinking / agent_tool / citation / done| API
    API -->|SSE| WEB
    WEB -->|管理员上传资料| API
    API --> INGEST --> MYSQL
    INGEST --> QWEN_EMB
    INGEST -->|双写/删除/状态同步| QDRANT
```

## AI 问诊请求时序

```mermaid
sequenceDiagram
    participant U as 用户
    participant W as kangban-web
    participant C as ConsultationController
    participant S as ConsultationService
    participant G as Java PythonAgentGateway
    participant A as Python 编排
    participant T as Java 内部工具 / RAG / 提案
    participant Q as Qwen
    participant DB as MySQL

    U->>W: 选择当前患者并发送问题
    W->>C: POST /consultation/sessions/{id}/messages
    C->>S: 保存用户消息并校验会话权限
    S-->>W: 返回 messageId
    W->>C: GET /stream?messageId=...
    C->>S: 创建 SSE 流
    S->>G: 已授权上下文、数据库快照及当前会话历史
    G->>A: 签名上下文、运行选项、截止时间
    A->>T: 带服务身份和签名上下文的回调
    T->>DB: 仅按服务端身份查询
    DB-->>T: 结构化数据 / 引用
    T-->>A: 工具结果和检索证据
    A->>Q: 系统提示 + 患者上下文 + 证据 + 对话历史
    Q-->>A: 最终答案或下一轮工具调用
    A-->>G: 答案、引用、工具轨迹、结果分类
    G-->>S: 校验版本和 runId 后的原结果结构
    S->>DB: 保存完整回复与原消息关联
    S-->>W: 原 SSE 事件（含完整回答）
    W-->>U: 丝滑展示最终回答和依据
```

## 关键模块职责

| 模块 | 职责 | 关键边界 |
| --- | --- | --- |
| Python `orchestrator.py` | 规划、工具循环、记忆、证据组装和最终生成 | 不直接访问业务数据库、MCP 或确认写入 |
| `PythonAgentGateway` | 签名上下文、内部 HTTP、预算/取消、严格结果校验和运行指标 | 不回退旧 Java 编排，不自动重试 |
| `AgentExecutionContext` | 携带用户、患者、成员、家庭和运行 ID | 由服务端签名，不能信任客户端身份字段 |
| `AgentToolRegistry` | 注册当前允许使用的工具 | 当前只允许只读工具 |
| `AgentToolExecutor` | 校验上下文、执行工具、记录状态和耗时 | 越权或写工具直接阻断 |
| `KnowledgeDocumentService` | 文档上传、解析、切片、审核、发布和重建索引 | 只有发布且有切片的文档可被公共检索 |
| `RoutingKnowledgeSearchService` | 按 `mysql-jdbc`、`dual`、`qdrant` 选择检索链路 | 默认 MySQL；Qdrant 失败不生成无依据回答 |
| `QdrantKnowledgeSearchService` | Qdrant Top-K 召回、MySQL 正文回查、权限复核和混合排序 | Qdrant 只保存可重建索引，不保存业务事实 |
| `VectorIndexSyncService` | 文档入库、发布、撤回和删除时同步 Qdrant | 写入失败记录 `vector_index_sync`，不吞掉 qdrant 模式故障 |
| `ConsultationService` | 保存会话消息并把 Agent 事件转为 SSE | 处理超时、重试和断线收尾 |
| Python `providers.py` | Mock、Qwen Chat / Tool Calling、DeepSeek 普通问诊适配 | API Key 只从环境变量读取 |

## 数据隔离

- 公共知识库只返回 `PUBLISHED` 且未删除的文档；
- 私有病历检索同时限制 owner、subject、family 和 member 范围；
- Agent 上下文由认证用户和服务端当前患者选择共同生成；
- 前端传入的用户 ID 不能覆盖服务端身份；
- 医疗写操作不由 Agent 工具直接执行，需要用户确认后进入领域接口。

## 故障策略

- Qwen Chat 超时：SSE 返回可重试错误，并保留会话状态；
- Qwen Embedding 不可用：RAG 返回 503，不静默切换为无依据医疗回答；
- 无检索证据：Agent 明确说明资料不足，并建议咨询专业人员；
- 工具异常：记录安全的错误类型和指标，不把病历正文写入日志；
- SSE 断线：服务端收尾，前端可以基于已保存的 messageId 重试。

旧 Java 编排、规划、记忆、安全及问诊模型客户端已删除，Python 基线对照保留。具体进程、配置与检查范围见 [迁移交付说明](python-agent-delivery.md)。
