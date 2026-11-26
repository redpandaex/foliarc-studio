"use client";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { DataTable } from "@/components/ui/data-table";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";

import { useCallback, useEffect, useState } from "react";
import { AdminShell } from "@/components/admin-shell";
import {
  AdminApiError,
  adminTracesApi,
  type AgentRunRow,
  type AgentRunTraceDetail,
  type GuardrailAuditRow,
} from "@/lib/admin-api";

export default function AdminTracesPage() {
  const [runs, setRuns] = useState<AgentRunRow[]>([]);
  const [guardrails, setGuardrails] = useState<GuardrailAuditRow[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [detail, setDetail] = useState<AgentRunTraceDetail | null>(null);
  const [statusFilter, setStatusFilter] = useState("");
  const [traceIdFilter, setTraceIdFilter] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);

  const loadRuns = useCallback(async (filter?: string, traceId?: string) => {
    setError(null);
    try {
      const [r, g] = await Promise.all([
        adminTracesApi.listRuns(filter || undefined, 40, traceId || undefined),
        adminTracesApi.listGuardrails(40),
      ]);
      setRuns(r);
      setGuardrails(g);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载会话追踪失败");
    }
  }, []);

  useEffect(() => {
    let active = true;
    Promise.all([
      adminTracesApi.listRuns(statusFilter || undefined, 40, traceIdFilter || undefined),
      adminTracesApi.listGuardrails(40),
    ])
      .then(([r, g]) => {
        if (!active) return;
        setRuns(r);
        setGuardrails(g);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(
          err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载会话追踪失败",
        );
      });
    return () => {
      active = false;
    };
  }, [statusFilter, traceIdFilter]);

  async function selectRun(runId: string) {
    setSelectedRunId(runId);
    setLoadingDetail(true);
    setError(null);
    try {
      const trace = await adminTracesApi.getRunTrace(runId);
      setDetail(trace);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "读取轨迹详情失败");
    } finally {
      setLoadingDetail(false);
    }
  }

  return (
    <AdminShell
      title="会话日志与全链路追踪中心"
      subtitle="按 run_id 检索结构化瀑布流（路由 → 工具调用 → 审校评分 → 护栏拦截 → Token 结算，已过滤 token 流与密钥脱敏）"
      actions={
        <div className="flex items-center gap-2">
          <Input
            aria-label="按 X-Trace-Id 检索"
            value={traceIdFilter}
            onChange={(e) => setTraceIdFilter(e.target.value)}
            placeholder="按 X-Trace-Id 检索..."
            className="w-44 font-mono"
          />
          <Select
            id="statusFilter"
            label="状态筛选"
            hideLabel
            value={statusFilter}
            onValueChange={(value) => value !== null && setStatusFilter(value)}
            options={[
              { value: "", label: "全部运行状态" },
              { value: "COMPLETED", label: "COMPLETED" },
              { value: "RUNNING", label: "RUNNING" },
              { value: "FAILED", label: "FAILED" },
              { value: "PAUSED", label: "PAUSED" },
            ]}
          />
          <Button
            type="button"
            onClick={() => void loadRuns(statusFilter, traceIdFilter)}
            variant="outline"
            size="sm"
          >
            刷新
          </Button>
        </div>
      }
    >
      {error && (
        <Alert status="danger" announce="assertive">
          {error}
        </Alert>
      )}

      <section className="grid gap-6 lg:grid-cols-12">
        {/* Left: Agent Runs List */}
        <Card className="p-4 lg:col-span-5">
          <h2 className="mb-3 text-sm font-semibold">最近 Agent 会话 ({runs.length})</h2>
          <div className="max-h-[560px] space-y-2 overflow-y-auto pr-1">
            {runs.map((run) => {
              const active = run.id === selectedRunId;
              return (
                <Button
                  key={run.id}
                  type="button"
                  onClick={() => void selectRun(run.id)}
                  aria-pressed={active}
                  variant="outline"
                  className={`block h-auto w-full whitespace-normal p-3 text-left ${active ? "border-primary bg-accent text-accent-foreground" : ""}`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-[11px] font-semibold">
                      {run.action} · {run.id.slice(0, 10)}
                    </span>
                    <Badge
                      status={
                        run.status === "FAILED"
                          ? "danger"
                          : run.status === "COMPLETED"
                            ? "success"
                            : "warning"
                      }
                    >
                      {run.status}
                    </Badge>
                  </div>
                  <div className="mt-1 flex items-center justify-between font-mono text-[10px] text-muted-foreground">
                    <span>{run.modelAlias ?? "default"}</span>
                    <span>
                      in:{run.promptTokens} / cache:{run.cachedTokens} / out:{run.completionTokens}
                    </span>
                  </div>
                </Button>
              );
            })}
            {runs.length === 0 && <EmptyState title="暂无 Agent 运行记录" />}
          </div>
        </Card>

        {/* Right: Structured Waterfall Timeline */}
        <Card className="p-4 lg:col-span-7">
          <h2 className="mb-3 text-sm font-semibold">结构化执行瀑布流 (agent_run_events)</h2>
          {loadingDetail ? (
            <p className="py-10 text-center text-xs text-muted-foreground">加载轨迹中...</p>
          ) : !detail ? (
            <EmptyState title="点击左侧任意会话查看完整节点事件、检查点与安全护栏明细" />
          ) : (
            <div className="space-y-4">
              <Card className="p-3 font-mono text-[11px]">
                <div>run_id: {detail.run.id}</div>
                <div className="mt-1 text-muted-foreground">
                  status={detail.run.status} · action={detail.run.action} · model=
                  {detail.run.modelAlias ?? "-"}
                </div>
                {detail.run.errorMessage && (
                  <div className="mt-1 text-status-danger">error: {detail.run.errorMessage}</div>
                )}
              </Card>

              <div className="space-y-2">
                <div className="text-xs font-medium text-muted-foreground">
                  事件时间轴 ({detail.events.length})
                </div>
                {detail.events.length === 0 ? (
                  <EmptyState title="该会话尚无落盘事件" />
                ) : (
                  <ol className="space-y-2">
                    {detail.events.map((ev) => (
                      <li
                        key={ev.id}
                        className="rounded-lg border border-border/80 bg-background/40 p-2.5 text-xs"
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2 font-mono text-[11px]">
                          <div>
                            <span className="text-muted-foreground">#{ev.seq}</span>
                            <Badge className="ml-2" status="info">
                              {ev.eventType}
                            </Badge>
                            <span className="ml-2 text-status-warning">{ev.agentName}</span>
                          </div>
                          <div className="text-[10px] text-muted-foreground">
                            {ev.latencyMs}ms · trace:{ev.traceId.slice(0, 12)}
                          </div>
                        </div>
                        <pre className="mt-2 max-h-40 overflow-x-auto whitespace-pre-wrap rounded bg-muted p-2 font-mono text-[10px] text-muted-foreground">
                          {ev.payloadJson}
                        </pre>
                      </li>
                    ))}
                  </ol>
                )}
              </div>

              {detail.checkpoints.length > 0 && (
                <div className="space-y-1.5">
                  <div className="text-xs font-medium text-muted-foreground">
                    HITL / 协作检查点 ({detail.checkpoints.length})
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {detail.checkpoints.map((cp) => (
                      <Badge key={cp.id} status="ai">
                        {cp.nodeName} · {cp.status}
                      </Badge>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </Card>
      </section>

      {/* Guardrail Audit Logs */}
      <Card className="p-4">
        <h2 className="mb-3 text-sm font-semibold">全局安全护栏拦截记录 ({guardrails.length})</h2>
        <DataTable
          caption="全局安全护栏拦截记录"
          rows={guardrails}
          rowKey={(g) => g.id}
          empty={<EmptyState title="暂无护栏触发记录" />}
          columns={[
            {
              id: "stage",
              header: "阶段 / 动作",
              className: "font-mono text-[11px]",
              cell: (g) => (
                <Badge status="danger">
                  {g.stage}:{g.action}
                </Badge>
              ),
            },
            {
              id: "rule",
              header: "规则编码",
              className: "font-mono text-[11px]",
              cell: (g) => g.ruleCode,
            },
            {
              id: "detail",
              header: "详情",
              className: "text-muted-foreground",
              cell: (g) => g.detail,
            },
            {
              id: "created",
              header: "时间",
              className: "font-mono text-[10px] text-muted-foreground",
              cell: (g) => (g.createdAt ? new Date(g.createdAt).toLocaleString("zh-CN") : "-"),
            },
          ]}
        />
      </Card>
    </AdminShell>
  );
}
