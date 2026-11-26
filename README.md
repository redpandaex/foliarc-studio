# FoliarcStudio（卷弧工坊）

> 企业级长篇小说创作 AI Agent 平台 — **Next.js（作者端 + 独立运营后台） + Java 21 Spring Boot 4（多模块业务网关 + 独立后台 API） + Python FastAPI（多 Agent / Hybrid RAG / 硬化沙箱 / Eval 引擎）** 四端解耦 Monorepo，采用 **PostgreSQL 16 + Milvus Standalone + Redis 7 + MinIO + RabbitMQ** 存算分离架构。

- 全栈学习清单与 Phase 0–6 实战路线图 👉 [docs/ROADMAP.md](./docs/ROADMAP.md)
- 万章级跨体裁长篇架构总览（5.8A–5.8D） 👉 [docs/architecture/00-ARCHITECTURE-OVERVIEW.md](./docs/architecture/00-ARCHITECTURE-OVERVIEW.md)
- VIP 动态权益中台设计 👉 [docs/architecture/05-VIP-ENTITLEMENTS-SYSTEM.md](./docs/architecture/05-VIP-ENTITLEMENTS-SYSTEM.md)
- 独立运营后台与 Java 多模块拆分方案 👉 [docs/architecture/06-ADMIN-SYSTEM-SEPARATION.md](./docs/architecture/06-ADMIN-SYSTEM-SEPARATION.md)

---

## 核心特性（Phase 0 – Phase 6 完整闭环）

- **作者端创作工作台 (`apps/web :3000`)**：卷/章/小节柔性目录树、自定义 SSE 断点续连状态机（`Last-Event-ID` + Pause/Resume/Cancel + HITL 大纲审批）、**划词改写/扩写 + Git 式逐段 Accept/Reject Diff 审阅**、黄金文风锚点一键收藏、双向自选范围蝴蝶效应改稿抽屉与个人资料库。
- **独立运营治理后台 (`apps/admin :3001` + `admin-java :8081`)**：前后端与作者端物理隔离、独立数据库连接池（`max=6`）与双 Audience JWT 隔离（仅放行 `aud=foliarc-admin` + `ROLE_ADMIN`）；涵盖公共/定向 RAG 资料库与多作用域检索调试、异步任务中心（Outbox / 文档 / Ripple 一键重试）、结构化会话链路瀑布图、计费与 VIP 四档热更新/授信、Prompt 模板版本发布、操作审计与 **Golden Eval 一键评测中心**。
- **Java 21 多模块业务与网关中台 (`services/java`)**：`foliarc-common` 共享领域与 V1–V13 Flyway 迁移（仅 `biz-java` 执行迁移）、JWT 双 Token 轮换、Redis Streams SSE 桥接、Redis+Lua 双维 RPM/TPM 限流、Resilience4j 熔断舱壁、两阶段幂等计费账本（Freeze + Settle）、Transactional Outbox + RabbitMQ 可靠投递。
- **Python Agent 与万章记忆引擎 (`services/agent-py :8000`)**：手写 ReAct Loop、11 套分层题材 Skill 渐进披露、`novel-lore-mcp`、6 大题材档案包、4 层对数记忆树 + 10 万字 Map-Reduce 蒸馏、Milvus Coarse-to-Fine + 三层作用域防剧透混合检索、6 态伏笔双层防剧透导演台、双向蝴蝶效应四阶段流水线、输入/输出安全护栏、六重硬化 Docker 沙箱与 30 用例自动化 Golden Eval 套件。

---

## 目录结构

```text
foliarc-studio/
├── apps/
│   ├── web/                  # 作者端 Next.js 16 (:3000) — 极简创作台、划词改写、Git Diff 审阅、SSE 状态机
│   └── admin/                # 独立运营后台 Next.js 16 (:3001) — 公共库、异步任务、会话追踪、计费/VIP、Eval 中心
├── services/
│   ├── java/                 # Java 21 + Spring Boot 4.1 + MyBatis-Plus Maven 多模块工程
│   │   ├── foliarc-common/   # 共享 Entity / Mapper / DTO / 领域服务 / Flyway V1–V13 迁移
│   │   ├── biz-java/         # 作者端业务中台 & AI 网关 (:8080)
│   │   └── admin-java/       # 独立运营后台 API (:8081，独立连接池 + aud=foliarc-admin + 写操作审计)
│   └── agent-py/             # Python 3.12 + FastAPI (uv, :8000) — Multi-Agent、MCP、Hybrid RAG、沙箱、Eval
├── contracts/                # 跨服务共享契约（SSE & Trace 事件协议 Schema）
├── evals/                    # 30 条跨题材路由、防剧透 RAG、蝴蝶效应与安全护栏黄金评测集
├── infra/
│   └── docker-compose.yml    # 分层 profiles：core / rag / app / admin / all（共 12 服务）
├── docs/
│   ├── ROADMAP.md            # 全栈学习清单与 Phase 0–6 实战路线
│   └── architecture/         # 00–06 核心架构与演进设计文档
├── .github/workflows/ci.yml  # 按目录路径独立触发的四端 CI 流水线（web / admin / java / agent-py）
├── Makefile                  # 统一容器编排、本地开发与四端测试入口
└── .env.example              # 本地环境变量模板
```

---

## 快速启动

### 方式一：Docker Compose 一键容器化启动（推荐）

```bash
# 1. 初始化环境变量（填入 LLM_API_KEY 等配置）
make env

# 2. 按需启动对应 Profile：
make full-up     # 全量 12 服务（PG + Redis + Milvus + MinIO + RabbitMQ + 作者端 + 独立后台）
make app-up      # 仅启动基础中间件 + 作者端栈（agent-py :8000 + biz-java :8080 + web :3000）
make admin-up    # 启动独立运营后台栈（admin-java :8081 + admin :3001）
make infra-ps    # 查看容器健康状态
```

### 方式二：本地源码分终端调试

```bash
# 基础设施（PostgreSQL 16 + Redis 7；需要向量库再执行 make infra-rag-up）
make infra-core-up

# 按需在独立终端启动各服务：
make dev-py          # Python Agent Engine (:8000)
make dev-java        # Java 21 Author Biz Gateway (:8080，自动锁定 JDK 21)
make dev-admin-java  # Java 21 Independent Admin API (:8081)
make dev-web         # Next.js Author Studio (:3000)
make dev-admin-web   # Next.js Ops Console (:3001)
```

---

## 健康检查端点

- **Author Web**: `http://localhost:3000` · `http://localhost:3000/api/health`
- **Admin Console**: `http://localhost:3001` · `http://localhost:3001/api/health`
- **Java Biz & Gateway**: `http://localhost:8080/api/v1/health` · `http://localhost:8080/api/v1/health/agent`
- **Java Admin API**: `http://localhost:8081/api/v1/admin/health`
- **Python Agent Engine**: `http://localhost:8000/health` · `http://localhost:8000/health/ready`

---

## 四端自动化测试与检查

```bash
make test-all    # 依次执行 test-py（含 30 用例 Golden Eval）、test-java（三模块）、test-web、test-admin
```
