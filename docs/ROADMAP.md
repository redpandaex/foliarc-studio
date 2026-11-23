# InkFlow Studio（墨流工坊）：企业级小说创作 Agent 平台实战路线图与学习清单

> **定位**：面向求职与全栈进阶的企业级 AI Agent 项目，采用 **Next.js（前端/交互层） + Java 21 Spring Boot（业务与网关计费层） + Python FastAPI（Agent 与 RAG 引擎层）** 三端解耦 Monorepo 架构，关系型数据使用 **PostgreSQL**，向量检索独立采用企业级 **Milvus**。
>
> **为什么选“长篇小说 Agent”作为求职项目？**
> 面试官已经看腻了“套壳 ChatGPT”和“简单知识库问答”。长篇小说创作天然倒逼你解决工业界最硬核的 Agent 难题：**超长上下文一致性（防吃书/人设崩塌）、长耗时流式任务的断点续连与人工介入（HITL）、多 Agent 分工评审、PG + Milvus 双写一致的混合检索、硬化容器沙箱执行、高并发 Token 预扣与精准结算**。

---

## 一、已定稿项目配置与技术栈

| 维度 | 最终选型 | 说明 |
| :--- | :--- | :--- |
| **项目名 & 仓库** | **InkFlow Studio** (`~/agent-project/inkflow-studio`) | 单仓 Monorepo（按目录路径独立触发 CI） |
| **前端** | **Next.js (App Router + TS)** + `pnpm` (`fnm`) + Tailwind CSS + `shadcn/ui` | 富文本编辑（Tiptap）、Diff 审阅、自研 SSE 状态机 |
| **业务与网关** | **Java 21 + Spring Boot 4.1 + MyBatis-Plus + Maven (`./mvnw`)** | 虚拟线程、MyBatis-Plus `@Version` 乐观锁、统一 AI 网关、RPM/TPM 限流、幂等计费账本 |
| **Agent 引擎** | **Python 3.12 + FastAPI + `uv`** | 手写 Agent Loop / Tool / Skill / MCP / 上下文压缩 / Hybrid RAG / 硬化 Docker 沙箱 |
| **模型接入** | **统一 OpenAI 兼容网关 + 模型别名路由** | 主力 DeepSeek + Gemini Flash / 聚合网关（含 Embedding & Rerank） |
| **数据存储** | **PostgreSQL 16** + **Milvus Standalone** + **Redis 7** + **MinIO** | PG 管强事务与状态；Milvus 专攻混合向量检索（复用 MinIO） |
| **本地基础设施** | **WSL 复用 Windows Docker Desktop + Compose `profiles`** | `core`(PG+Redis) / `rag`(MinIO+etcd+Milvus) / `obs`(Langfuse) 按需启停 |
| **代码/工具沙箱** | **`SandboxProvider` 抽象 + 硬化 Docker 容器沙箱** | 六重安全硬化 + 预热池 + 僵尸回收；配置化预留 gVisor (`runsc`) / E2B 扩展 |

---

## 二、企业级三层系统架构总览

明确 **Java 与 Python 的职责边界** 与 **PG 与 Milvus 的存算分离** 是面试必考题：
- **Next.js**：负责富文本写作体验、流式协议解析、Diff 审阅、前端容错状态机。
- **Java Spring Boot**：发挥高并发与强事务优势，负责认证鉴权、多租户权限、小说/章节版本管理、**统一 AI 网关（限流/熔断）**、**分布式计费账本**、异步任务编排与流式桥接。
- **Python FastAPI**：发挥 AI 生态优势，专注 **Agent Loop、Tool/Skill/MCP、上下文压缩、Hybrid RAG（Milvus）、文档解析、硬化 Docker 沙箱、多 Agent 编排与评测护栏**。

```mermaid
flowchart LR
    subgraph FE["Frontend (Next.js + pnpm)"]
        UI["Novel Editor & Copilot UI"]
        SSEClient["SSE State Machine (Resume/Pause/Reconnect)"]
    end

    subgraph BIZ["Business & Gateway (Java 21 Spring Boot)"]
        GW["API Gateway (Auth / RPM-TPM Rate Limit)"]
        BIZCore["Novel CRUD & Version Control"]
        Billing["Token Billing & Idempotent Ledger"]
        SSEProxy["SSE Bridge & Replay Service"]
    end

    subgraph AGENT["Agent Engine (Python FastAPI + uv)"]
        Router["Intent & Model Router"]
        Loop["Multi-Agent Orchestrator & Checkpoint"]
        Tools["Tool / Skill Registry & MCP Client"]
        Sandbox["Hardened Docker Sandbox (Warm Pool)"]
        Ctx["Context Manager & Compression"]
        RAG["Hybrid RAG & Document Ingestion"]
        Guard["Guardrails & Eval Engine"]
    end

    subgraph INFRA["Data & Observability (Docker Compose Profiles)"]
        PG["PostgreSQL 16 (Biz / Ledger / Checkpoint)"]
        Milvus["Milvus Standalone (Dense+Sparse Vector)"]
        Redis["Redis 7 (Streams / Lock / Cache)"]
        MinIO["MinIO (PDF/Word + Milvus Storage)"]
        MQ["Redis Streams / RabbitMQ"]
        Obs["OpenTelemetry + Langfuse"]
    end

    UI --> SSEClient
    SSEClient <-->|"HTTP / SSE + Last-Event-ID"| GW
    GW --> BIZCore
    GW --> Billing
    GW --> SSEProxy
    SSEProxy <-->|"Internal Stream / HTTP"| Router
    BIZCore --> PG
    Billing --> PG
    Billing --> Redis
    SSEProxy <--> Redis
    BIZCore --> MinIO
    BIZCore --> MQ
    MQ --> RAG
    Router --> Loop
    Loop --> Tools
    Tools --> Sandbox
    Loop --> Ctx
    Loop --> RAG
    Loop --> Guard
    RAG --> Milvus
    RAG --> PG
    Milvus --> MinIO
    Loop --> PG
    Loop --> Redis
    GW -.-> Obs
    Loop -.-> Obs
```

---

## 三、全栈技能与功能实现清单（可逐项打勾）

> [!TIP]
> 标有 `★` 的是**企业级面试高频追问的核心加分项**。

### 模块 1：前端交互与流式工程（Next.js + TypeScript）

- [x] **1.1 自定义 SSE 客户端（弃用原生 `EventSource`）**
  - 使用 `fetch` + `ReadableStream` + `TextDecoder` 实现支持 `POST`、`Authorization` Header 和自定义协议的 SSE 解析器（处理 TCP 拆包/粘包、跨 chunk 换行拼接）。
- [x] **1.2 结构化流式事件协议（Structured Stream Protocol）** `★`
  - 统一事件类型：`run_started`、`thought_delta`（思考过程）、`tool_call`、`tool_result`、`text_delta`（正文流）、`interrupt`（等待人工确认）、`usage`（Token 消耗）、`error`、`done`。
- [x] **1.3 SSE 断点续连（Resumable Stream）**
  - 前端持久化当前 `run_id` 与最后收到的 `last_event_id`；页面刷新、切网或断线后携带 `Last-Event-ID` 请求后端，从 Redis Streams 补发缺失分片，无缝衔接不丢字、不重复生成。
- [x] **1.4 自动重连与心跳检测**
  - 指数退避 + 随机抖动（Exponential Backoff + Jitter）重连策略、最大重试上限、服务端 `ping` 心跳超时检测、浏览器 `online/offline` 事件感知。
- [x] **1.5 暂停、继续与取消（Pause / Resume / Cancel）**
  - **前端取消**：`AbortController` 断开连接并发送 `POST /runs/{run_id}/cancel`。
  - **后端协作式暂停与恢复**：前端发送 `pause`，Agent 在安全检查点挂起并保存状态；用户修改指令或直接点 `resume` 后从检查点继续流式输出。
- [ ] **1.6 小说编辑器与 AI 协作体验** `★`
  - 集成富文本编辑器（Tiptap / Lexical），支持划词改写/扩写、AI 生成内容 **Git 式 Diff 审阅（逐段 Accept / Reject）**、防抖自动保存 + 乐观锁版本冲突提示。
- [ ] **1.7 Agent 执行轨迹可视化面板** `★`
  - 类似 Cursor/Manus 的实时步骤时间轴：展示当前是哪个 Agent 在工作、调用了哪些 Tool/MCP/Sandbox、命中了哪些设定文档、本次消耗了多少 Token 与耗时。

---

### 模块 2：Agent 核心引擎（Python + FastAPI）

> [!IMPORTANT]
> **学习策略**：核心机制（Agent Loop、Tool Calling、上下文压缩、状态机）**务必先用纯 Python + OpenAI 兼容 SDK 手写一遍**，彻底吃透底层后再引入或对比 LangGraph 等框架，这样面试被问底层原理时才能对答如流。

- [ ] **2.1 Agent Loop（从零手写执行循环）**
  - 实现 ReAct 循环（Thought -> Action -> Observation -> Final Answer）与 Plan-and-Execute 模式。
  - **防失控机制** `★`：最大步数限制（`max_steps`）、Token 预算熔断、死循环检测（连续调用相同 Tool + 相同参数时强制干预）、优雅降级输出。
- [ ] **2.2 Tool 体系（工具注册与健壮执行）**
  - Pydantic 强类型定义 Tool Schema，支持并行 Tool Calls、超时控制、自动重试、幂等键。
  - **工具错误自愈** `★`：参数校验失败或执行报错时，返回结构化错误提示让 LLM 自我修正而非直接崩溃。
  - 小说专属工具示例：`get_character_state`、`update_foreshadowing`、`search_worldbook`、`read_prev_chapter_summary`、`check_timeline_conflict`、`run_analysis_script`（走沙箱）。
- [ ] **2.3 Skill 系统（模块化写作技能包）**
  - 设计**渐进式披露（Progressive Disclosure）**机制：System Prompt 仅放轻量 Skill 目录（名称+触发场景），Agent 按需加载完整 Skill 规范、示例及受控 `scripts/`（例如：`打斗场景描写 Skill`、`悬疑伏笔埋设 Skill`、`古风对话润色 Skill`、`卡文破局 Skill`、`全书节奏统计 Skill`），节省大量上下文 Token。
- [ ] **2.4 MCP（Model Context Protocol）实战**
  - 实现 **MCP Client/Host** 接入 Agent Runtime，动态发现工具（`tools/list`）与资源（`resources/read`）。
  - 手写 1~2 个专属 **MCP Server**（如 `novel-lore-mcp` 暴露人物/世界观库，支持 `stdio` 和 `Streamable HTTP` 两种传输协议），并在服务端实现租户/用户权限透传。
- [ ] **2.5 Prompt 编排与版本管理**
  - 基于 Jinja2 的模板化 Prompt 组合（角色设定 + 写作禁忌 + 风格样本 + 动态记忆 + 结构化输出约束）。
  - **Prompt 注册中心** `★`：支持版本号、灰度/A-B 测试、回滚（存数据库而非写死在代码里）。
- [ ] **2.6 上下文管理与压缩（Context Engineering）**
  - **Token 预算分配器**：精确计算（`tiktoken`）各区域配额（System + Skill + 长期记忆 + RAG 片段 + 近期对话 + 输出预留）。
  - **分层压缩策略**：滑动窗口 + 滚动摘要（Rolling Summary）+ 大工具输出截断与外置（Tool Output Offloading，只给摘要和引用 ID）。
  - **KV Cache 前缀优化（Prompt Caching）** `★`：将静态 System Prompt、固定世界观设定放在前缀最前面，动态指令放尾部，显著降低 TTFT 延迟与 50%~80% 的输入成本。
- [ ] **2.7 多 Agent 架构与协作编排**
  - 设计“小说编辑部”多 Agent 团队：
    - `Director（总编/规划师）`：拆解章节大纲与场景节拍（Beat Sheet）。
    - `Lorekeeper（设定管家）`：检索世界观、人物状态与未回收伏笔。
    - `Writer（执笔作家）`：按指定文风与 Skill 撰写正文。
    - `ContinuityCritic（审稿/查重编辑）`：检查人设 OOC、时间线矛盾、战力崩坏，不通过则打回重写（Reflection 循环，限制最大打回次数如 2 次）。
  - **状态共享模式**：基于 Shared State（黑板模式）+ PostgreSQL Checkpoint 持久化，避免 Agent 间“传话失真”。
  - **HITL（Human-in-the-Loop）中断与恢复** `★`：如 `Director` 生成章节大纲后主动触发 `interrupt`，等待前端用户修改/批准后再唤醒 `Writer` 写正文。
- [ ] **2.8 Agent 路由与模型调度（Routing & Fallback）**
  - **多层意图路由**：规则/关键词快速通道 -> Embedding 语义路由 -> 轻量小模型分类 -> LLM 决策。
  - **智能模型路由（Model Routing）** `★`：简单任务（摘要提取、意图分类、实体抽取）路由至廉价快模型；核心任务（高潮剧情创作、逻辑审稿）路由至旗舰模型。
  - **容灾降级（Fallback Chain）**：主模型 429 限流或 5xx 超时时自动无缝切换备用模型/厂商。
- [ ] **2.9 Agent 准确性与安全护栏（Guardrails）**
  - **输入护栏**：Prompt 注入检测（防套取系统 Prompt）、越狱拦截、敏感词/内容合规审核（国内网文合规刚需）。
  - **输出护栏**：结构化 Schema 校验、**小说一致性核验**（对比人物当前状态与时间线防止幻觉/吃书）。
  - **高危工具审批**：批量修改世界观或覆盖已发布章节等写操作必须经过用户确认。
- [ ] **2.10 Agent 代码与工具执行沙箱（Hardened Docker Sandbox）** `★`
  - **业务落地场景**：小说角色出场/节奏/情绪曲线 Python 分析、战力/资产/时间线数值精确校验、Skill 附带脚本及 EPUB/PDF 成品导出、不可信文档解析隔离。
  - **抽象与实现**：定义 `SandboxProvider` 统一接口，亲手实现 **`DockerSandboxProvider`**，配置化预留 **gVisor (`runsc`)** 与 **E2B / Firecracker MicroVM** 扩展口。
  - **六重安全硬化**：`--network=none`（防 SSRF 与外泄）、`--read-only` + 限额 `tmpfs` `/tmp`、`--cap-drop=ALL` + `no-new-privileges` + 非 root 用户、`--memory`/`--cpus`/`--pids-limit`（防 OOM 与 Fork Bomb）、零宿主机密钥/环境变量透传、严格执行超时强杀。
  - **生命周期治理**：支持无状态短执行与有状态会话沙箱、**Warm Pool 预热池**（领用后阅后即焚绝不跨租户复用）、后台 **Reaper 僵尸容器巡检回收**、完整安全攻防自动化测试用例。

---

### 模块 3：小说级 RAG 与记忆引擎（Python + Milvus + PostgreSQL）

- [ ] **3.1 多格式文档异步导入流水线**
  - 支持 **PDF**（PyMuPDF / pdfplumber + 版面/表格解析，进阶可接入 OCR 处理扫描件）、**Word（`.docx`）**（保留标题层级结构）、**Markdown / TXT / EPUB**（导入已有小说原稿）。
  - **企业级异步解耦链路** `★`：前端直传/经 Java 存 MinIO -> 发送 MQ 消息 -> Python Worker 异步解析切片入库 -> 实时回写解析进度（解析中/切片中/向量化/完成/失败重试）。
- [ ] **3.2 小说感知文档切片（Chunking）**
  - 对比实现：固定长度切片、递归字符切片、**结构/语义切片**（按章/节/场景切分，绝不在人物对话中间切断）、**父子文档索引（Parent-Child / Small-to-Big）**（小块精准检索，返回大块完整上下文）。
  - 丰富元数据（Metadata）：`tenant_id`、`book_id`、`chapter_no`、`scene_id`、`characters`、`doc_type`（设定集/正文/文风参考）。
- [ ] **3.3 Milvus 企业级混合召回与精排（Hybrid Retrieval + Rerank）**
  - **向量仓储抽象层** `★`：定义 `VectorStore` 协议接口，以 **Milvus Standalone** 作为核心实现，支持按 `book_id` 配置 **Partition Key 多租户隔离**与 HNSW / IVF 索引选型对比。
  - **双路混合召回**：Milvus 原生 **稠密向量（Dense）+ 稀疏向量（BM25 / Sparse）** 双路召回，解决小说专有名词（人名、招式、地名）纯向量召回不准的痛点。
  - **融合与精排**：实现 **RRF（Reciprocal Rank Fusion）** 融合多路结果 + Cross-Encoder **Reranker**（如 BGE-Reranker）二次精排。
  - **查询改写（Query Transformation）** `★`：多查询扩展（Multi-Query）、代词消解（将“他刚才拿的剑”改写为“主角林青在第三章获得的玄铁剑”）。
  - **防剧透标量预过滤** `★`：修改第 15 章时，通过 Milvus 标量表达式强制过滤 `book_id == X and chapter_no <= 15`，避免检索到后续章节造成时间线错乱。
- [ ] **3.4 PG 与 Milvus 双写一致性（企业高频系统设计考点）** `★`
  - 修改或删除文档/章节时，基于 PostgreSQL 事务 + Outbox 异步同步 Milvus，通过 `doc_version` 版本号与软删除/幂等 `upsert/delete` 机制，彻底杜绝“PG 已删但向量库还能召回脏数据”。
- [ ] **3.5 结构化长程记忆 / 轻量 GraphRAG（小说杀手锏）** `★`
  - 每次章节定稿后，异步触发记忆抽取流水线写入 PostgreSQL：
    - 更新 **人物状态表**（位置、境界、伤势、持有物品、情绪关系）。
    - 更新 **伏笔追踪表**（埋设章节、描述、状态：未回收/已回收）。
    - 更新 **人物关系三元组**（`角色A --关系/事件--> 角色B`）与 **事件时间线**。

---

### 模块 4：企业级业务中台、AI 网关与计费（Java 21 Spring Boot）

- [ ] **4.1 领域建模与多租户权限体系**
  - Spring Security + JWT（Access Token + Refresh Token 轮换）+ RBAC 权限控制 + 资源归属校验（防止越权读写他人小说/知识库）。
  - **章节 Git 式版本控制** `★`：支持历史快照、版本对比、一键回滚；Java 与 Python 内部服务间通过签名/内部 JWT 鉴权。
- [ ] **4.2 统一 AI 网关与流式代理（Gateway & SSE Bridge）**
  - Java 作为统一入口代理 Python 的 SSE 流（开启 JDK 21 虚拟线程，结合 `SseEmitter` / `StreamingResponseBody` 或 WebFlux `WebClient`）。
  - **Redis Streams 流缓冲层** `★`：Python 产出的每个事件先写入 Redis Stream（`XADD`，设 TTL）并推送给网关；网关断线重连时直接从 `Last-Event-ID` 位置 `XREAD` 补发，实现高可用断点续连。
  - **多维度限流**：Redis + Lua 脚本实现令牌桶/滑动窗口，同时控制 **RPM（每分钟请求数）** 和 **TPM（每分钟 Token 数）** `★`。
  - **韧性保护**：集成 Resilience4j 实现超时控制、熔断降级、舱壁隔离（Bulkhead，防止慢 LLM 请求拖垮业务线程池）。
- [ ] **4.3 企业级计费、配额与成本管控（Billing & Cost）** `★`
  - **预冻结 + 精准结算模型（两阶段扣费）**：
    1. 请求前根据模型单价与最大配额 **预冻结（Freeze）** 积分/余额。
    2. Agent 结束（或中断取消）后，按实际消耗的 `input_tokens`、`cached_tokens`、`output_tokens` + 工具/沙箱调用费率进行 **精准结算（Settle）**，退还多余冻结额度。
  - **防并发超扣与幂等性**：Redis 分布式锁 + 数据库乐观锁（`version`）+ 唯一 `request_id` 记账流水表（复式记账思想，支持对账与审计）。
  - **成本监控看板**：单章生成成本、模型占比、Prompt Cache 命中节省比例、租户月度预算告警与硬熔断。
- [ ] **4.4 可靠异步事件（Outbox + MQ）** `★`
  - 使用本地消息表（Transactional Outbox）+ RabbitMQ/Kafka，保证“文档上传/章节定稿/计费结算”与数据库事务的最终一致性，配备死信队列（DLQ）与消费幂等处理。

---

### 模块 5：可观测性、评测与工程交付（LLMOps & DevOps）`★`

- [ ] **5.1 跨语言全链路追踪（Distributed Tracing）**
  - 使用 OpenTelemetry 打通 **Next.js -> Spring Boot -> Python FastAPI -> LLM/DB**，通过统一 `trace_id` 串联全链路耗时。
- [ ] **5.2 LLM 可观测性（Tracing & Metrics）**
  - 私有化部署开源 **Langfuse**（或自建 Trace 面板），监控每次运行的 **TTFT（首字延迟）**、**TPOT（单 Token 生成耗时）**、Token 成本、Agent 轨迹树、失败率与缓存命中率。
- [ ] **5.3 Agent 与 RAG 自动化评测体系（Eval）**
  - 构建 30~50 条小说场景黄金评测集（Golden Dataset）：
    - **RAG 评测**：检索召回率（Recall@K）、Faithfulness（忠实度）、Context Precision（可借鉴 RAGAS 思路）。
    - **Agent 轨迹评测**：是否调用了正确工具、步数是否冗余。
    - **小说质量 Rubric 评测（LLM-as-a-Judge）**：设定一致性、人物 OOC 检查、文风贴合度打分，做到“改 Prompt 有数据对比，不靠盲猜”。
- [ ] **5.4 容器化与工程规范**
  - 一键 `docker-compose.yml` 分层 profiles（`core`: PostgreSQL 16 + Redis 7；`rag`: MinIO + etcd + Milvus Standalone；`obs`: Langfuse + OTel）、统一 OpenAPI/事件契约文档、按路径触发的三端 CI 单元测试与代码检查。

---

## 四、应该从哪里开始？（7 阶段垂直切片实战路线）

> [!WARNING]
> **切忌“横向瀑布式开发”！** 不要花一个月先写完 Java 的权限、计费、RBAC 却还没跑通一句 AI 生成；也不要在 Python 里堆砌 10 个 Agent 却没有前后端联调。
> **正确姿势是“垂直切片（Vertical Slice）”**：每 1~2 周跑通一个端到端可演示的闭环，逐层叠加企业级能力。

```mermaid
flowchart TD
    P0["Phase 0: 工程基座 + 基础CRUD (第1周)"] --> P1["Phase 1: 端到端SSE流式链路与断点续连 (第2-3周)"]
    P1 --> P2["Phase 2: 手写Agent Loop + Tool/Skill/MCP + 上下文压缩 (第4-5周)"]
    P2 --> P3["Phase 3: Milvus Hybrid RAG + 文档异步导入 + 记忆库 (第6-7周)"]
    P3 --> P4["Phase 4: 多Agent协同 + 路由 + HITL中断恢复 (第8-9周)"]
    P4 --> P5["Phase 5: 企业级网关 + 计费账本 + 安全护栏 + 硬化Docker沙箱 (第10-11周)"]
    P5 --> P6["Phase 6: 全链路追踪 + Eval评测 + 简历与面试包装 (第12周)"]
```

### 各阶段落地目标与简历产出

1. **Phase 0：工程基座与最小闭环（第 1 周）**
   - **做什么**：初始化 Monorepo（`apps/web`、`services/biz-java`、`services/agent-py`、`contracts/`、`infra/docker-compose.yml`），通过 `--profile core` 拉起 PostgreSQL 16 + Redis 7；跑通登录鉴权 + 小说/章节 CRUD。
   - **里程碑**：能新建一本小说、创建并保存章节。

2. **Phase 1：端到端企业级 SSE 流式链路（第 2~3 周）—— 强烈建议以此作为核心技术第一枪！**
   - **做什么**：打通 `Next.js -> Spring Boot Gateway -> Python FastAPI -> LLM` 全链路流式吐字；引入 **Redis Streams** 实现 `Last-Event-ID` 断点续连、指数退避重连、心跳检测与取消/暂停信号。
   - **里程碑**：生成正文时**直接刷新浏览器或模拟断网再恢复**，文字不丢失、不重算、无缝续接！
   - **简历亮点**：攻克高并发长耗时 AI 流式传输的高可用与断点续传难题。

3. **Phase 2：手写 Agent Core + Tool / Skill / MCP + 上下文压缩（第 4~5 周）**
   - **做什么**：纯 Python 手写 ReAct Agent Loop；封装小说写作 Tool 集与 `SandboxProvider` 抽象接口；实现渐进式 Skill 加载；开发自定义 `novel-lore-mcp` Server 并用 MCP Client 接入；实现 Token 预算控制、滚动摘要与 KV Cache 前缀优化。
   - **里程碑**：Agent 能自主查人物设定、按需挂载写作 Skill、在上下文逼近上限时自动压缩历史。
   - **简历亮点**：不依赖黑盒框架，深入掌握 Agent 状态循环、MCP 协议与 Context Engineering。

4. **Phase 3：Milvus 小说级 Hybrid RAG + 异步文档解析 + 结构化记忆（第 6~7 周）**
   - **做什么**：通过 `--profile rag` 拉起 MinIO + Milvus Standalone；Java 接 PDF/Word/TXT 上传至 MinIO + 异步通知 Python Worker 解析切片；落地 **Milvus Partition Key 多租户 + Dense/BM25 双路混合检索 + RRF + Rerank + 防剧透标量过滤 + PG/Milvus 双写一致性**；章节定稿后自动抽取人物状态与伏笔表。
   - **里程碑**：导入 50 页世界观 PDF 设定集，AI 写作能精准引用设定细节且不出现前后矛盾，删改设定后检索结果即时同步。
   - **简历亮点**：解决传统纯向量 RAG 专有名词召回难、长篇一致性差及关系库与向量库双写一致性难题。

5. **Phase 4：多 Agent 协作编排 + 路由 + HITL 暂停恢复（第 8~9 周）**
   - **做什么**：实现基于数据库 Checkpoint 的状态机；编排 `Director -> Lorekeeper -> Writer -> Critic` 多 Agent 流水线；支持大纲阶段 `interrupt` 等待用户审批修改后再继续；接入意图路由与大小模型分级路由 + Fallback。
   - **里程碑**：一键“创作下一章” -> 自动出大纲并暂停等你微调 -> 点击继续 -> 自动查设定写正文 -> 审稿编辑打分不通过自动返工 -> 输出终稿。
   - **简历亮点**：落地工业级 Multi-Agent 协作、Human-in-the-Loop 状态恢复与多模型降本路由。

6. **Phase 5：企业级网关、计费账本、安全护栏与硬化 Docker 沙箱（第 10~11 周）**
   - **做什么**：在 Spring Boot 落地 Redis+Lua 的 RPM/TPM 双维限流、Resilience4j 熔断、**预冻结 + 精准结算**幂等扣费账本；在 Python 侧补齐输入防注入/合规审核、输出一致性护栏，并落地 **`DockerSandboxProvider`（六重容器硬化 + 预热池 + 僵尸回收 + 安全攻防测试用例）**。
   - **里程碑**：高并发压测下余额不超扣、触发 TPM 限流优雅降级、Prompt 注入被拦截、沙箱攻防用例全部拦截通过（禁外网/禁读密钥/防 Fork Bomb/超时强杀）。
   - **简历亮点**：补齐高并发 Java 后端分布式幂等计费、限流熔断与企业级 Agent 纵深安全隔离体系。

7. **Phase 6：可观测性、自动化评测与求职冲刺（第 12 周）**
   - **做什么**：接入 OpenTelemetry 跨语言链路追踪 + Langfuse；跑通 30+ 场景 Eval 评测集，量化记录优化前后指标（如：TTFT 降低 X%、检索 Recall@5 提升 Y%、KV Cache 节省 Z% 成本、长篇一致性错误率下降 W%）。
   - **里程碑**：沉淀架构图、核心技术复盘文档与可演示 Demo，直接转化为简历 STAR 案例。

---

## 五、自研 vs 选用成熟组件边界建议

| 强烈建议亲手写（面试必抠细节） | 建议直接用成熟生态（避免重复造轮子） |
| :--- | :--- |
| Agent Loop（ReAct / Reflection / 状态流转） | Web 框架：Next.js (`pnpm`)、Spring Boot 3 (Java 21)、FastAPI (`uv`) |
| SSE 解析器 + Redis Streams 断点续连协议 | 富文本编辑器与 UI：Tiptap、`shadcn/ui` |
| 上下文预算分配器与压缩策略 | 存储层：PostgreSQL 16 + **Milvus Standalone** + MinIO |
| `VectorStore` 抽象层 + RRF 融合 + PG/Milvus 双写同步 | MCP 协议传输层：官方 Python `mcp` SDK |
| `SandboxProvider` 抽象 + 硬化 Docker 沙箱 & 预热池 | 底层容器引擎与运行时：Docker Engine（可扩展 gVisor / E2B） |
| 分布式预扣/结算账本 & Redis+Lua 限流器 | 熔断与观测：Resilience4j、OpenTelemetry、Langfuse |
| 评测脚本与小说一致性 Rubric | 文档基础解析器：PyMuPDF、`python-docx` |
