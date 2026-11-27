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
import { adminEvalApi, type EvalReportResponse } from "@/lib/admin-api";
import { formatAdminError } from "@/lib/error-messages";

const CATEGORY_LABELS: Record<string, string> = {
  GENRE_ROUTING: "题材匹配",
  GENRE_RUBRIC: "文风校验",
  RAG_ANTI_SPOILER: "防剧透检索",
  RIPPLE_FACT_DIFF: "改稿一致性",
  GUARDRAILS: "安全拦截",
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
      setError(formatAdminError(err, "运行评测失败，请稍后重试", { action: "eval" }));
    } finally {
      setRunning(false);
    }
  }

  const visibleCases = (report?.cases ?? []).filter(
    (c) => categoryFilter === "ALL" || c.category === categoryFilter,
  );

  return (
    <AdminShell
      title="质量评测"
      actions={
        report ? (
          <Button
            type="button"
            loading={running}
            onClick={() => void handleRunEval()}
            variant="default"
            size="sm"
          >
            {running ? "评测中…" : "重新运行"}
          </Button>
        ) : undefined
      }
    >
      {error && (
        <Alert
          status="danger"
          announce="assertive"
          action={
            <Button
              type="button"
              variant="outline"
              size="sm"
              loading={running}
              onClick={() => void handleRunEval()}
            >
              重试
            </Button>
          }
        >
          {error}
        </Alert>
      )}

      {!report ? (
        <EmptyState
          title="尚未运行评测"
          action={
            <Button
              type="button"
              loading={running}
              onClick={() => void handleRunEval()}
              variant="default"
              size="sm"
            >
              {running ? "评测中…" : "运行评测"}
            </Button>
          }
        />
      ) : (
        <>
          <section className="grid gap-4 sm:grid-cols-3">
            <KpiCard
              label="通过率"
              value={`${(report.overall_pass_rate * 100).toFixed(1)}%`}
              status={
                report.overall_pass_rate >= 1
                  ? "success"
                  : report.overall_pass_rate >= 0.85
                    ? "warning"
                    : "danger"
              }
              detail={`${report.passed_cases} / ${report.total_cases} 通过`}
            />
            <KpiCard label="总耗时" value={`${report.duration_ms} ms`} status="info" />
            <KpiCard
              label="评测维度"
              value={`${Object.keys(report.category_scores).length} 项`}
            />
          </section>

          <Card className="p-4">
            <h2 className="mb-3 text-sm font-semibold">分维度通过率</h2>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              {Object.entries(report.category_scores).map(([cat, score]) => {
                const pct = Math.round(score * 100);
                return (
                  <Card key={cat} className="p-3 text-xs">
                    <div className="font-medium">{CATEGORY_LABELS[cat] ?? cat}</div>
                    <div className="mt-1 font-mono text-lg font-bold text-status-success tabular-nums">
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
                分项结果 ({visibleCases.length} / {report.total_cases})
              </h2>
              <Select
                id="categoryFilter"
                label="维度筛选"
                hideLabel
                value={categoryFilter}
                onValueChange={(value) => value !== null && setCategoryFilter(value)}
                options={[
                  { value: "ALL", label: "全部维度" },
                  ...Object.keys(report.category_scores).map((cat) => ({
                    value: cat,
                    label: CATEGORY_LABELS[cat] ?? cat,
                  })),
                ]}
              />
            </div>
            <DataTable
              caption="评测分项结果"
              rows={visibleCases}
              rowKey={(item) => item.case_id}
              empty={<EmptyState title="当前维度暂无用例" />}
              columns={[
                {
                  id: "id",
                  header: "用例",
                  className: "font-mono font-semibold",
                  cell: (item) => item.case_id,
                },
                {
                  id: "category",
                  header: "维度",
                  className: "text-xs text-muted-foreground",
                  cell: (item) => CATEGORY_LABELS[item.category] ?? item.category,
                },
                {
                  id: "status",
                  header: "结果",
                  cell: (item) => (
                    <Badge status={item.passed ? "success" : "danger"}>
                      {item.passed ? "通过" : "未通过"}
                    </Badge>
                  ),
                },
                {
                  id: "detail",
                  header: "说明",
                  className: "text-muted-foreground",
                  cell: (item) => item.detail || "-",
                },
                {
                  id: "latency",
                  header: "耗时",
                  align: "end",
                  className: "font-mono text-xs text-muted-foreground tabular-nums",
                  cell: (item) => <>{item.latency_ms} ms</>,
                },
              ]}
            />
          </Card>
        </>
      )}
    </AdminShell>
  );
}
