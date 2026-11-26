"use client";

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
        setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载会话追踪失败");
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
          <input
            value={traceIdFilter}
            onChange={(e) => setTraceIdFilter(e.target.value)}
            placeholder="按 X-Trace-Id 检索..."
            className="w-44 rounded-md border border-border bg-card px-2.5 py-1.5 font-mono text-xs outline-none focus:border-blue-500"
          />
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="rounded-md border border-border bg-card px-2.5 py-1.5 text-xs"
          >
            <option value="">全部运行状态</option>
            <option value="COMPLETED">COMPLETED</option>
            <option value="RUNNING">RUNNING</option>
            <option value="FAILED">FAILED</option>
            <option value="PAUSED">PAUSED</option>
          </select>
          <button
            type="button"
            onClick={() => void loadRuns(statusFilter, traceIdFilter)}
            className="rounded-md border border-border bg-card px-3 py-1.5 text-xs hover:bg-muted"
          >
            刷新
          </button>
        </div>
      }
    >
      {error && (
        <div className="rounded-lg border border-rose-500/40 bg-rose-500/10 px-4 py-2 text-xs text-rose-300">
          {error}
        </div>
      )}

      <section className="grid gap-6 lg:grid-cols-12">
        {/* Left: Agent Runs List */}
        <div className="rounded-xl border border-border bg-card p-4 lg:col-span-5">
          <h2 className="mb-3 text-sm font-semibold">最近 Agent 会话 ({runs.length})</h2>
          <div className="max-h-[560px] space-y-2 overflow-y-auto pr-1">
            {runs.map((run) => {
              const active = run.id === selectedRunId;
              return (
                <button
                  key={run.id}
                  type="button"
                  onClick={() => void selectRun(run.id)}
                  className={`w-full rounded-lg border p-3 text-left text-xs transition-colors ${
                    active
                      ? "border-blue-500 bg-blue-500/10"
                      : "border-border/80 bg-background/40 hover:bg-muted/60"
                  }`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-[11px] font-semibold">
                      {run.action} · {run.id.slice(0, 10)}
                    </span>
                    <span
                      className={`rounded px-1.5 py-0.5 font-mono text-[10px] ${
                        run.status === "FAILED"
                          ? "bg-rose-500/20 text-rose-300"
                          : run.status === "COMPLETED"
                            ? "bg-emerald-500/20 text-emerald-300"
                            : "bg-amber-500/20 text-amber-300"
                      }`}
                    >
                      {run.status}
                    </span>
                  </div>
                  <div className="mt-1 flex items-center justify-between font-mono text-[10px] text-muted-foreground">
                    <span>{run.modelAlias ?? "default"}</span>
                    <span>
                      in:{run.promptTokens} / cache:{run.cachedTokens} / out:{run.completionTokens}
                    </span>
                  </div>
                </button>
              );
            })}
            {runs.length === 0 && (
              <p className="py-8 text-center text-xs text-muted-foreground">暂无 Agent 运行记录</p>
            )}
          </div>
        </div>

        {/* Right: Structured Waterfall Timeline */}
        <div className="rounded-xl border border-border bg-card p-4 lg:col-span-7">
          <h2 className="mb-3 text-sm font-semibold">结构化执行瀑布流 (agent_run_events)</h2>
          {loadingDetail ? (
            <p className="py-10 text-center text-xs text-muted-foreground">加载轨迹中...</p>
          ) : !detail ? (
            <p className="py-10 text-center text-xs text-muted-foreground">
              点击左侧任意会话查看完整节点事件、检查点与安全护栏明细
            </p>
          ) : (
            <div className="space-y-4">
              <div className="rounded-lg border border-border bg-background/60 p-3 font-mono text-[11px]">
                <div>run_id: {detail.run.id}</div>
                <div className="mt-1 text-muted-foreground">
                  status={detail.run.status} · action={detail.run.action} · model=
                  {detail.run.modelAlias ?? "-"}
                </div>
                {detail.run.errorMessage && (
                  <div className="mt-1 text-rose-300">error: {detail.run.errorMessage}</div>
                )}
              </div>

              <div className="space-y-2">
                <div className="text-xs font-medium text-muted-foreground">
                  事件时间轴 ({detail.events.length})
                </div>
                {detail.events.length === 0 ? (
                  <p className="text-xs text-muted-foreground">该会话尚无落盘事件</p>
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
                            <span className="ml-2 rounded bg-blue-500/20 px-1.5 py-0.5 text-blue-300">
                              {ev.eventType}
                            </span>
                            <span className="ml-2 text-amber-300">{ev.agentName}</span>
                          </div>
                          <div className="text-[10px] text-muted-foreground">
                            {ev.latencyMs}ms · trace:{ev.traceId.slice(0, 12)}
                          </div>
                        </div>
                        <pre className="mt-2 max-h-40 overflow-x-auto whitespace-pre-wrap rounded bg-black/30 p-2 font-mono text-[10px] text-muted-foreground">
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
                      <span
                        key={cp.id}
                        className="rounded border border-violet-500/40 bg-violet-500/10 px-2 py-1 font-mono text-[10px] text-violet-200"
                      >
                        {cp.nodeName} · {cp.status}
                      </span>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </section>

      {/* Guardrail Audit Logs */}
      <section className="rounded-xl border border-border bg-card p-4">
        <h2 className="mb-3 text-sm font-semibold">全局安全护栏拦截记录 ({guardrails.length})</h2>
        {guardrails.length === 0 ? (
          <p className="py-4 text-center text-xs text-muted-foreground">暂无护栏触发记录</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="border-b border-border text-[11px] text-muted-foreground">
                <tr>
                  <th className="py-2">阶段 / 动作</th>
                  <th className="py-2">规则编码</th>
                  <th className="py-2">详情</th>
                  <th className="py-2">时间</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {guardrails.map((g) => (
                  <tr key={g.id}>
                    <td className="py-2 font-mono text-[11px]">
                      <span className="rounded bg-rose-500/20 px-1.5 py-0.5 text-rose-300">
                        {g.stage}:{g.action}
                      </span>
                    </td>
                    <td className="py-2 font-mono text-[11px]">{g.ruleCode}</td>
                    <td className="py-2 text-muted-foreground">{g.detail}</td>
                    <td className="py-2 font-mono text-[10px] text-muted-foreground">
                      {g.createdAt ? new Date(g.createdAt).toLocaleString("zh-CN") : "-"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </AdminShell>
  );
}
