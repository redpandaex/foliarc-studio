"use client";

import { useState } from "react";
import { AdminShell } from "@/components/admin-shell";
import {
  AdminApiError,
  adminEvalApi,
  type EvalReportResponse,
} from "@/lib/admin-api";

const CATEGORY_LABELS: Record<string, string> = {
  GENRE_ROUTING: "跨题材 Skill 路由",
  GENRE_RUBRIC: "题材防 AI 味 Rubric",
  RAG_ANTI_SPOILER: "多作用域防剧透 RAG",
  RIPPLE_FACT_DIFF: "蝴蝶效应 Fact Diff",
  GUARDRAILS: "输入/输出安全护栏",
};

export default function AdminEvalsPage() {
  const [report, setReport] = useState<EvalReportResponse | null>(null);
  const [categoryFilter, setCategoryFilter] = useState("ALL");
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleRunEval() {
    setRunning(true);
    setError(null);
    try {
      const res = await adminEvalApi.run();
      setReport(res);
    } catch (err) {
      setError(
        err instanceof AdminApiError
          ? `[${err.code}] ${err.message}`
          : "触发 Golden Eval 评测失败（请确认 agent-py :8000 已启动）",
      );
    } finally {
      setRunning(false);
    }
  }

  const visibleCases = (report?.cases ?? []).filter(
    (c) => categoryFilter === "ALL" || c.category === categoryFilter,
  );

  return (
    <AdminShell
      title="Golden Eval 自动化评测中心"
      subtitle="一键执行 evals/golden_dataset.json（30 条跨题材路由、防剧透 RAG、蝴蝶效应与护栏回归评测）"
      actions={
        <button
          type="button"
          disabled={running}
          onClick={() => void handleRunEval()}
          className="rounded-md bg-blue-600 px-3.5 py-1.5 text-xs font-medium text-white hover:bg-blue-500 disabled:opacity-50"
        >
          {running ? "正在执行 30 条黄金评测..." : "▶ 一键运行 Golden Eval"}
        </button>
      }
    >
      {error && (
        <div className="rounded-lg border border-rose-500/40 bg-rose-500/10 px-4 py-2.5 text-xs text-rose-300">
          {error}
        </div>
      )}

      {!report ? (
        <div className="rounded-xl border border-dashed border-border bg-card/40 p-10 text-center">
          <p className="text-sm font-medium">尚未执行本次会话评测</p>
          <p className="mt-1.5 text-xs text-muted-foreground">
            点击右上角「一键运行 Golden Eval」，将经由 <code className="font-mono">admin-java → agent-py</code>{" "}
            执行全部 30 条小说领域核心能力回归测试，并自动写入后台操作审计。
          </p>
        </div>
      ) : (
        <>
          <section className="grid gap-4 sm:grid-cols-3">
            <div className="rounded-xl border border-border bg-card p-4">
              <div className="text-xs text-muted-foreground">综合通过率 (Pass Rate)</div>
              <div
                className={`mt-1 font-mono text-2xl font-bold ${
                  report.overall_pass_rate >= 1
                    ? "text-emerald-400"
                    : report.overall_pass_rate >= 0.85
                      ? "text-amber-300"
                      : "text-rose-400"
                }`}
              >
                {(report.overall_pass_rate * 100).toFixed(1)}%
              </div>
              <div className="mt-1 font-mono text-[11px] text-muted-foreground">
                {report.passed_cases} / {report.total_cases} cases passed
              </div>
            </div>

            <div className="rounded-xl border border-border bg-card p-4">
              <div className="text-xs text-muted-foreground">评测总耗时</div>
              <div className="mt-1 font-mono text-2xl font-bold text-blue-400">
                {report.duration_ms} ms
              </div>
              <div className="mt-1 text-[11px] text-muted-foreground">
                确定性零成本本地离线向量 + 护栏 + 路由全链路回归
              </div>
            </div>

            <div className="rounded-xl border border-border bg-card p-4">
              <div className="text-xs text-muted-foreground">覆盖评测维度</div>
              <div className="mt-1 font-mono text-2xl font-bold">
                {Object.keys(report.category_scores).length} 大维度
              </div>
              <div className="mt-1 text-[11px] text-muted-foreground">
                每次 Prompt / Skill / RAG 迭代可量化对比回归结果
              </div>
            </div>
          </section>

          <section className="rounded-xl border border-border bg-card p-4">
            <h2 className="mb-3 text-sm font-semibold">分维度得分总览</h2>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              {Object.entries(report.category_scores).map(([cat, score]) => {
                const pct = Math.round(score * 100);
                return (
                  <div
                    key={cat}
                    className="rounded-lg border border-border/80 bg-background/50 p-3 text-xs"
                  >
                    <div className="font-medium">{CATEGORY_LABELS[cat] ?? cat}</div>
                    <div className="mt-1 font-mono text-lg font-bold text-emerald-400">
                      {pct}%
                    </div>
                    <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                      <div
                        className="h-full bg-emerald-500"
                        style={{ width: `${Math.min(100, pct)}%` }}
                      />
                    </div>
                  </div>
                );
              })}
            </div>
          </section>

          <section className="rounded-xl border border-border bg-card p-4">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h2 className="text-sm font-semibold">
                用例明细 ({visibleCases.length} / {report.total_cases})
              </h2>
              <select
                value={categoryFilter}
                onChange={(e) => setCategoryFilter(e.target.value)}
                className="rounded-md border border-border bg-background px-2.5 py-1 text-xs"
              >
                <option value="ALL">全部维度</option>
                {Object.keys(report.category_scores).map((cat) => (
                  <option key={cat} value={cat}>
                    {CATEGORY_LABELS[cat] ?? cat} ({cat})
                  </option>
                ))}
              </select>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="border-b border-border text-[11px] text-muted-foreground">
                  <tr>
                    <th className="py-2">ID</th>
                    <th className="py-2">维度</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">诊断详情</th>
                    <th className="py-2 text-right">耗时</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {visibleCases.map((item) => (
                    <tr key={item.case_id}>
                      <td className="py-2 font-mono font-semibold">{item.case_id}</td>
                      <td className="py-2 font-mono text-[11px] text-muted-foreground">
                        {item.category}
                      </td>
                      <td className="py-2">
                        <span
                          className={`rounded px-1.5 py-0.5 font-mono text-[10px] font-semibold ${
                            item.passed
                              ? "bg-emerald-500/20 text-emerald-300"
                              : "bg-rose-500/20 text-rose-300"
                          }`}
                        >
                          {item.passed ? "PASS" : "FAIL"}
                        </span>
                      </td>
                      <td className="py-2 text-muted-foreground">{item.detail}</td>
                      <td className="py-2 text-right font-mono text-[11px] text-muted-foreground">
                        {item.latency_ms}ms
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
    </AdminShell>
  );
}
