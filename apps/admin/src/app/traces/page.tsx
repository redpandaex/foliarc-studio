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
  adminTracesApi,
  type AgentRunRow,
  type AgentRunTraceDetail,
  type GuardrailAuditRow,
} from "@/lib/admin-api";
import {
  badgeForStatus,
  labelEventType,
  labelGuardrailAction,
  labelGuardrailStage,
  labelRunAction,
  labelStatus,
} from "@/lib/admin-labels";
import { formatAdminError, formatJobFailureReason } from "@/lib/error-messages";

interface ParsedEventField {
  label: string;
  value: string;
}

const PAYLOAD_FIELD_MAP: [string, string][] = [
  ["intent", "意图"],
  ["route", "路由"],
  ["model", "模型"],
  ["tool", "工具"],
  ["tool_name", "工具"],
  ["score", "评分"],
  ["decision", "结论"],
  ["passed", "通过"],
  ["reason", "原因"],
  ["summary", "摘要"],
  ["status", "状态"],
  ["hits", "命中数"],
  ["count", "数量"],
];

function extractReadablePayload(payloadJson: string): ParsedEventField[] {
  if (!payloadJson) return [];
  try {
    const parsed = JSON.parse(payloadJson) as Record<string, unknown>;
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return [];
    const fields: ParsedEventField[] = [];
    for (const [key, label] of PAYLOAD_FIELD_MAP) {
      const val = parsed[key];
      if (val === undefined || val === null || val === "") continue;
      if (typeof val === "boolean") {
        fields.push({ label, value: val ? "是" : "否" });
      } else if (typeof val === "number") {
        fields.push({ label, value: String(val) });
      } else if (typeof val === "string") {
        fields.push({ label, value: val.slice(0, 120) });
      } else if (Array.isArray(val)) {
        fields.push({ label, value: `${val.length} 项` });
      }
      if (fields.length >= 4) break;
    }
    return fields;
  } catch {
    return [];
  }
}

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
      setError(formatAdminError(err, "加载会话记录失败，请稍后重试"));
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
        setError(formatAdminError(err, "加载会话记录失败，请稍后重试"));
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
      setError(formatAdminError(err, "读取会话详情失败，请稍后重试"));
    } finally {
      setLoadingDetail(false);
    }
  }

  const hasFilter = Boolean(statusFilter || traceIdFilter.trim());
  const sessionTraceId = detail?.events.find((e) => e.traceId)?.traceId ?? null;

  return (
    <AdminShell
      title="会话追踪"
      actions={
        <div className="flex flex-wrap items-center gap-2">
          <Input
            aria-label="追踪编号"
            value={traceIdFilter}
            onChange={(e) => setTraceIdFilter(e.target.value)}
            placeholder="追踪编号"
            className="w-44 font-mono"
          />
          <Select
            id="statusFilter"
            label="状态筛选"
            hideLabel
            value={statusFilter}
            onValueChange={(value) => value !== null && setStatusFilter(value)}
            options={[
              { value: "", label: "全部状态" },
              { value: "COMPLETED", label: "已完成" },
              { value: "RUNNING", label: "运行中" },
              { value: "FAILED", label: "失败" },
              { value: "PAUSED", label: "待确认" },
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
        <Alert
          status="danger"
          announce="assertive"
          action={
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => void loadRuns(statusFilter, traceIdFilter)}
            >
              重新加载
            </Button>
          }
        >
          {error}
        </Alert>
      )}

      {/* Current Session Explorer */}
      <section className="grid gap-6 lg:grid-cols-12">
        <Card className="p-4 lg:col-span-5">
          <h2 className="mb-3 text-sm font-semibold">最近会话 ({runs.length})</h2>
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
                  className={`block h-auto w-full whitespace-normal p-3 text-left ${
                    active ? "border-primary bg-accent text-accent-foreground" : ""
                  }`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-xs font-semibold">
                      {labelRunAction(run.action)} ·{" "}
                      <span className="font-mono text-[11px] font-normal text-muted-foreground">
                        {run.id.slice(0, 10)}
                      </span>
                    </span>
                    <Badge status={badgeForStatus(run.status)}>{labelStatus(run.status)}</Badge>
                  </div>
                  <div className="mt-1.5 flex items-center justify-between text-[11px] text-muted-foreground">
                    <span>{run.modelAlias ?? "默认模型"}</span>
                    <span className="font-mono tabular-nums">
                      输入 {run.promptTokens} / 缓存 {run.cachedTokens} / 输出{" "}
                      {run.completionTokens}
                    </span>
                  </div>
                </Button>
              );
            })}
            {runs.length === 0 &&
              (hasFilter ? (
                <EmptyState
                  title="没有符合条件的会话"
                  action={
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setStatusFilter("");
                        setTraceIdFilter("");
                      }}
                    >
                      清除筛选
                    </Button>
                  }
                />
              ) : (
                <EmptyState title="暂无会话记录" />
              ))}
          </div>
        </Card>

        <Card className="p-4 lg:col-span-7">
          <h2 className="mb-3 text-sm font-semibold">当前会话明细</h2>
          {loadingDetail ? (
            <p className="py-10 text-center text-xs text-muted-foreground">加载中…</p>
          ) : !detail ? (
            <EmptyState title="选择左侧会话查看执行步骤与确认记录" />
          ) : (
            <div className="space-y-4">
              <div className="grid gap-2 rounded-lg border border-border bg-background/50 p-3 text-xs sm:grid-cols-2">
                <div>
                  <span className="text-muted-foreground">会话编号：</span>
                  <span className="font-mono">{detail.run.id}</span>
                </div>
                {sessionTraceId && (
                  <div>
                    <span className="text-muted-foreground">追踪编号：</span>
                    <span className="font-mono">{sessionTraceId}</span>
                  </div>
                )}
                <div className="flex items-center gap-2">
                  <span className="text-muted-foreground">动作与状态：</span>
                  <span>{labelRunAction(detail.run.action)}</span>
                  <Badge status={badgeForStatus(detail.run.status)}>
                    {labelStatus(detail.run.status)}
                  </Badge>
                </div>
                <div>
                  <span className="text-muted-foreground">模型：</span>
                  <span>{detail.run.modelAlias ?? "默认模型"}</span>
                </div>
                {detail.run.errorMessage && (
                  <div className="sm:col-span-2 text-status-danger">
                    异常：{formatJobFailureReason(detail.run.errorMessage, "执行异常")}
                  </div>
                )}
              </div>

              <div className="space-y-2">
                <div className="text-xs font-medium text-muted-foreground">
                  执行步骤 ({detail.events.length})
                </div>
                {detail.events.length === 0 ? (
                  <EmptyState title="暂无步骤记录" className="py-6" />
                ) : (
                  <ol className="space-y-2">
                    {detail.events.map((ev) => {
                      const fields = extractReadablePayload(ev.payloadJson);
                      return (
                        <li
                          key={ev.id}
                          className="rounded-lg border border-border/80 bg-background/40 p-3 text-xs"
                        >
                          <div className="flex flex-wrap items-center justify-between gap-2">
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="font-mono text-[11px] text-muted-foreground">
                                #{ev.seq}
                              </span>
                              <Badge status="info">{labelEventType(ev.eventType)}</Badge>
                              {ev.agentName && (
                                <span className="font-medium text-foreground">{ev.agentName}</span>
                              )}
                            </div>
                            <span className="font-mono text-[11px] text-muted-foreground tabular-nums">
                              {ev.latencyMs} ms
                            </span>
                          </div>
                          {fields.length > 0 && (
                            <dl className="mt-2 grid gap-x-4 gap-y-1 text-[11px] sm:grid-cols-2">
                              {fields.map((f) => (
                                <div key={`${ev.id}-${f.label}`} className="flex gap-1.5">
                                  <dt className="shrink-0 text-muted-foreground">{f.label}：</dt>
                                  <dd className="min-w-0 truncate text-foreground">{f.value}</dd>
                                </div>
                              ))}
                            </dl>
                          )}
                        </li>
                      );
                    })}
                  </ol>
                )}
              </div>

              {detail.checkpoints.length > 0 && (
                <div className="space-y-1.5">
                  <div className="text-xs font-medium text-muted-foreground">
                    人工确认 ({detail.checkpoints.length})
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {detail.checkpoints.map((cp) => (
                      <Badge key={cp.id} status={badgeForStatus(cp.status)}>
                        {cp.nodeName} · {cp.status === "PAUSED" ? "待确认" : "已处理"}
                      </Badge>
                    ))}
                  </div>
                </div>
              )}

              {detail.guardrailLogs.length > 0 && (
                <div className="space-y-1.5">
                  <div className="text-xs font-medium text-muted-foreground">
                    本会话安全拦截 ({detail.guardrailLogs.length})
                  </div>
                  <div className="space-y-1.5">
                    {detail.guardrailLogs.map((g) => (
                      <div
                        key={g.id}
                        className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-status-danger-border bg-status-danger-bg/40 px-3 py-2 text-xs"
                      >
                        <div className="flex items-center gap-2">
                          <Badge status="danger">{labelGuardrailStage(g.stage)}</Badge>
                          <span className="font-medium">{g.ruleCode}</span>
                          {g.detail && <span className="text-muted-foreground">{g.detail}</span>}
                        </div>
                        <Badge status="neutral">{labelGuardrailAction(g.action)}</Badge>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </Card>
      </section>

      {/* Site-wide Guardrails */}
      <Card className="p-4">
        <h2 className="mb-3 text-sm font-semibold">全站拦截记录 ({guardrails.length})</h2>
        <DataTable
          caption="全站拦截记录"
          rows={guardrails}
          rowKey={(g) => g.id}
          empty={<EmptyState title="暂无拦截记录" />}
          columns={[
            {
              id: "created",
              header: "时间",
              className: "text-xs text-muted-foreground tabular-nums",
              cell: (g) => (g.createdAt ? new Date(g.createdAt).toLocaleString("zh-CN") : "-"),
            },
            {
              id: "stage",
              header: "环节",
              cell: (g) => <Badge status="warning">{labelGuardrailStage(g.stage)}</Badge>,
            },
            {
              id: "rule",
              header: "触发规则",
              className: "font-mono text-xs",
              cell: (g) => g.ruleCode,
            },
            {
              id: "action",
              header: "处置结果",
              cell: (g) => <Badge status="danger">{labelGuardrailAction(g.action)}</Badge>,
            },
            {
              id: "detail",
              header: "详情",
              className: "text-muted-foreground",
              cell: (g) => g.detail || "-",
            },
          ]}
        />
      </Card>
    </AdminShell>
  );
}
