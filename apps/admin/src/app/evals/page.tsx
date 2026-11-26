"use client";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { DataTable } from "@/components/ui/data-table";
import { EmptyState } from "@/components/ui/empty-state";
import { KpiCard } from "@/components/ui/kpi-card";
import { Select } from "@/components/ui/select";

import { useState } from "react";
import { AdminShell } from "@/components/admin-shell";
import { AdminApiError, adminEvalApi, type EvalReportResponse } from "@/lib/admin-api";

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
        <Button
          type="button"
          loading={running}
          onClick={() => void handleRunEval()}
          variant="default"
          size="sm"
        >
          {running ? "正在执行 30 条黄金评测..." : "▶ 一键运行 Golden Eval"}
        </Button>
      }
    >
      {error && (
        <Alert status="danger" announce="assertive">
          {error}
        </Alert>
      )}

      {!report ? (
        <EmptyState
          title="尚未执行本次会话评测"
          description="点击右上角「一键运行 Golden Eval」，将经由 admin-java → agent-py 执行全部 30 条小说领域核心能力回归测试，并自动写入后台操作审计。"
        />
      ) : (
        <>
          <section className="grid gap-4 sm:grid-cols-3">
            <KpiCard
              label="综合通过率 (Pass Rate)"
              value={`${(report.overall_pass_rate * 100).toFixed(1)}%`}
              status={
                report.overall_pass_rate >= 1
                  ? "success"
                  : report.overall_pass_rate >= 0.85
                    ? "warning"
                    : "danger"
              }
              detail={`${report.passed_cases} / ${report.total_cases} cases passed`}
            />
            <KpiCard
              label="评测总耗时"
              value={`${report.duration_ms} ms`}
              status="info"
              detail="确定性零成本本地离线向量 + 护栏 + 路由全链路回归"
            />
            <KpiCard
              label="覆盖评测维度"
              value={`${Object.keys(report.category_scores).length} 大维度`}
              detail="每次 Prompt / Skill / RAG 迭代可量化对比回归结果"
            />
          </section>

          <Card className="p-4">
            <h2 className="mb-3 text-sm font-semibold">分维度得分总览</h2>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              {Object.entries(report.category_scores).map(([cat, score]) => {
                const pct = Math.round(score * 100);
                return (
                  <Card key={cat} className="p-3 text-xs">
                    <div className="font-medium">{CATEGORY_LABELS[cat] ?? cat}</div>
                    <div className="mt-1 font-mono text-lg font-bold text-status-success">
                      {pct}%
                    </div>
                    <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                      <div
                        className="h-full bg-status-success"
                        style={{ width: `${Math.min(100, pct)}%` }}
                      />
                    </div>
                  </Card>
                );
              })}
            </div>
          </Card>

          <Card className="p-4">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h2 className="text-sm font-semibold">
                用例明细 ({visibleCases.length} / {report.total_cases})
              </h2>
              <Select
                id="categoryFilter"
                label="评测维度"
                hideLabel
                value={categoryFilter}
                onValueChange={(value) => value !== null && setCategoryFilter(value)}
                options={[
                  { value: "ALL", label: "全部维度" },
                  ...Object.keys(report.category_scores).map((cat) => ({
                    value: cat,
                    label: `${CATEGORY_LABELS[cat] ?? cat} (${cat})`,
                  })),
                ]}
              />
            </div>
            <DataTable
              caption="用例明细"
              rows={visibleCases}
              rowKey={(item) => item.case_id}
              empty={<EmptyState title="当前维度暂无用例" />}
              columns={[
                {
                  id: "id",
                  header: "ID",
                  className: "font-mono font-semibold",
                  cell: (item) => item.case_id,
                },
                {
                  id: "category",
                  header: "维度",
                  className: "font-mono text-[11px] text-muted-foreground",
                  cell: (item) => item.category,
                },
                {
                  id: "status",
                  header: "状态",
                  cell: (item) => (
                    <Badge status={item.passed ? "success" : "danger"}>
                      {item.passed ? "PASS" : "FAIL"}
                    </Badge>
                  ),
                },
                {
                  id: "detail",
                  header: "诊断详情",
                  className: "text-muted-foreground",
                  cell: (item) => item.detail,
                },
                {
                  id: "latency",
                  header: "耗时",
                  align: "end",
                  className: "font-mono text-[11px] text-muted-foreground",
                  cell: (item) => <>{item.latency_ms}ms</>,
                },
              ]}
            />
          </Card>
        </>
      )}
    </AdminShell>
  );
}
