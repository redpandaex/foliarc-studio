# FoliarcStudio（卷弧工坊）

> 企业级长篇小说创作 AI Agent 平台 — **Next.js + Java 21 Spring Boot + Python FastAPI** 三端解耦 Monorepo，采用 **PostgreSQL 16 + Milvus Standalone + Redis 7 + MinIO** 存算分离架构。

完整学习清单、系统架构图与 7 阶段垂直切片路线图见 👉 [docs/ROADMAP.md](./docs/ROADMAP.md)。

---

## 目录结构

```text
foliarc-studio/
├── apps/
│   └── web/                  # Next.js (App Router + TS + Tailwind + shadcn/ui) 前端编辑器与 SSE 状态机
├── services/
│   ├── biz-java/             # Java 21 + Spring Boot 4.1 + MyBatis-Plus：业务中台、JWT 轮换认证、AI 网关与计费账本
│   └── agent-py/             # Python 3.12 + FastAPI (uv)：Agent Loop、Tool/Skill/MCP、Hybrid RAG、硬化 Docker 沙箱
├── contracts/                # 跨服务共享契约（SSE 事件协议 Schema）
├── evals/                    # 小说 Agent & RAG 黄金评测集（Phase 6）
├── infra/
│   └── docker-compose.yml    # 分层 profiles：core (PG+Redis) / rag (MinIO+etcd+Milvus)
├── docs/
│   └── ROADMAP.md            # 全栈学习清单与 7 阶段路线图
├── .github/workflows/ci.yml  # 按目录路径独立触发的三端 CI 流水线
├── Makefile                  # 统一开发与测试入口（自动为 biz-java 锁定 JDK 21）
└── .env.example              # 本地环境变量模板
```

---

## 快速启动（Phase 0）

### 1. 初始化环境变量并启动核心基础设施（PostgreSQL 16 + Redis 7）

```bash
make env
make infra-core-up
make infra-ps
```

> 默认只启动 `core` profile（PG + Redis，约 200~300MB 内存）；进入 Phase 3 做 RAG 时再运行 `make infra-rag-up` 拉起 MinIO + etcd + Milvus Standalone。

### 2. 启动三端服务（建议开三个终端）

```bash
# 终端 1：Python Agent Engine (:8000)
make dev-py

# 终端 2：Java 21 Business & AI Gateway (:8080，自动注入 JAVA_HOME=/usr/lib/jvm/temurin-21)
make dev-java

# 终端 3：Next.js Frontend (:3000)
make dev-web
```

### 3. 健康检查端点

- Frontend: `http://localhost:3000` / `http://localhost:3000/api/health`
- Java Biz & Gateway: `http://localhost:8080/api/v1/health` & `http://localhost:8080/api/v1/health/agent`
- Python Agent Engine: `http://localhost:8000/health` & `http://localhost:8000/health/ready`

### 4. 运行三端检查与单测

```bash
make test-all
```
