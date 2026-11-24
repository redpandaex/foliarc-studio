import Link from "next/link";
import { ServiceStatus } from "@/components/service-status";
import { buttonVariants } from "@/components/ui/button";

const PHASES = [
  { id: "Phase 0", title: "工程基座、JWT 轮换认证与小说/章节/版本 CRUD 闭环", status: "已完成" },
  { id: "Phase 1", title: "端到端 SSE 流式链路与 Redis Streams 断点续连", status: "已完成" },
  { id: "Phase 2", title: "手写 Agent Loop + Tool / Skill / MCP + 上下文压缩", status: "已完成" },
  { id: "Phase 3", title: "Milvus Hybrid RAG + 异步文档导入 + 结构化记忆", status: "已完成" },
  { id: "Phase 4", title: "多 Agent 协同编排 + 路由 + HITL 中断恢复", status: "下一步" },
  { id: "Phase 5", title: "企业级网关 + 分布式计费账本 + 安全护栏与硬化 Docker 沙箱", status: "待开始" },
  { id: "Phase 6", title: "OpenTelemetry + Langfuse 可观测性与 Eval 评测", status: "待开始" },
];

export default function HomePage() {
  return (
    <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-8 px-6 py-12">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div className="space-y-2">
          <div className="inline-flex items-center rounded-full border border-border px-3 py-1 font-mono text-xs text-muted-foreground">
            FoliarcStudio · Phase 3 Ready
          </div>
          <h1 className="text-3xl font-bold tracking-tight">卷弧工坊 · FoliarcStudio</h1>
          <p className="text-sm text-muted-foreground">
            Next.js + Java 21 Spring Boot 4 (MyBatis-Plus) + Python FastAPI + PostgreSQL 16 + Milvus
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Link
            href="/login"
            className={buttonVariants({ variant: "outline", size: "sm" })}
          >
            登录 / 注册
          </Link>
          <Link href="/books" className={buttonVariants({ size: "sm" })}>
            进入书架与创作台 →
          </Link>
        </div>
      </header>

      <ServiceStatus />

      <section className="rounded-xl border border-border bg-card p-6 shadow-xs">
        <h2 className="text-base font-semibold">垂直切片实战路线</h2>
        <ol className="mt-4 space-y-2.5 text-sm">
          {PHASES.map((phase) => (
            <li
              key={phase.id}
              className="flex items-center justify-between gap-4 rounded-lg border border-border/60 px-3.5 py-2.5"
            >
              <div className="flex items-center gap-3">
                <span className="font-mono text-xs font-semibold text-muted-foreground">
                  {phase.id}
                </span>
                <span>{phase.title}</span>
              </div>
              <span className="font-mono text-xs text-muted-foreground">
                {phase.status}
              </span>
            </li>
          ))}
        </ol>
      </section>
    </main>
  );
}
