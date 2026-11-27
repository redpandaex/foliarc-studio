"use client";

import { Alert } from "@/components/ui/alert";
import { Badge, type UiStatus } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { DataTable } from "@/components/ui/data-table";
import { EmptyState } from "@/components/ui/empty-state";
import { KpiCard } from "@/components/ui/kpi-card";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { AdminShell } from "@/components/admin-shell";
import {
  adminAuditApi,
  adminOverviewApi,
  type AdminAuditLogRow,
  type AdminOverviewStats,
  type AdminUserRow,
} from "@/lib/admin-api";
import {
  labelAuditAction,
  labelPlan,
  labelRole,
  labelTargetType,
} from "@/lib/admin-labels";
import { formatAdminError } from "@/lib/error-messages";

export default function AdminOverviewPage() {
  const [stats, setStats] = useState<AdminOverviewStats | null>(null);
  const [users, setUsers] = useState<AdminUserRow[]>([]);
  const [audits, setAudits] = useState<AdminAuditLogRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const loadAll = useCallback(async () => {
    setError(null);
    try {
      const [s, u, a] = await Promise.all([
        adminOverviewApi.overview(),
        adminOverviewApi.listUsers(),
        adminAuditApi.list(15),
      ]);
      setStats(s);
      setUsers(u);
      setAudits(a);
    } catch (err) {
      setError(formatAdminError(err, "加载总览失败，请稍后重试"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let active = true;
    Promise.all([adminOverviewApi.overview(), adminOverviewApi.listUsers(), adminAuditApi.list(15)])
      .then(([s, u, a]) => {
        if (!active) return;
        setStats(s);
        setUsers(u);
        setAudits(a);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(formatAdminError(err, "加载总览失败，请稍后重试"));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const scaleKpis: { label: string; value: number; status: UiStatus }[] = stats
    ? [
        { label: "注册用户", value: stats.totalUsers, status: "neutral" },
        { label: "作品总数", value: stats.totalBooks, status: "neutral" },
        { label: "AI 会话数", value: stats.totalRuns, status: "info" },
        { label: "跨章修订", value: stats.totalRippleJobs, status: "info" },
      ]
    : [];

  const healthKpis: { label: string; value: number; status: UiStatus }[] = stats
    ? [
        {
          label: "投递失败",
          value: stats.failedOutboxEvents,
          status: stats.failedOutboxEvents > 0 ? "danger" : "success",
        },
        { label: "待处理资料", value: stats.pendingDocuments, status: "warning" },
        { label: "待确认设定", value: stats.pendingProposals, status: "ai" },
        {
          label: "安全拦截",
          value: stats.guardrailBlocksCount,
          status: stats.guardrailBlocksCount > 0 ? "warning" : "success",
        },
      ]
    : [];

  return (
    <AdminShell
      title="总览"
      actions={
        <Button type="button" onClick={() => void loadAll()} variant="outline" size="sm">
          刷新
        </Button>
      }
    >
      {error && (
        <Alert
          status="danger"
          announce="assertive"
          action={
            <Button type="button" variant="outline" size="sm" onClick={() => void loadAll()}>
              重新加载
            </Button>
          }
        >
          {error}
        </Alert>
      )}

      {loading ? (
        <div className="py-10 text-center text-xs text-muted-foreground">加载中…</div>
      ) : (
        <>
          {stats && (
            <div className="space-y-4">
              <section className="space-y-2">
                <h2 className="text-xs font-medium text-muted-foreground">平台规模</h2>
                <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                  {scaleKpis.map((item) => (
                    <KpiCard
                      key={item.label}
                      label={item.label}
                      value={item.value.toLocaleString()}
                      status={item.status}
                    />
                  ))}
                </div>
              </section>

              <section className="space-y-2">
                <h2 className="text-xs font-medium text-muted-foreground">运行情况</h2>
                <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
                  {healthKpis.map((item) => (
                    <KpiCard
                      key={item.label}
                      label={item.label}
                      value={item.value.toLocaleString()}
                      status={item.status}
                    />
                  ))}
                </div>
              </section>
            </div>
          )}

          <section className="grid gap-6 lg:grid-cols-2">
            <Card className="p-4">
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-sm font-semibold">最近注册用户 ({users.length})</h2>
                <Link href="/users" className="text-xs text-primary hover:underline">
                  全部用户 →
                </Link>
              </div>
              <DataTable
                caption="最近注册用户"
                rows={users.slice(0, 12)}
                rowKey={(u) => u.id}
                empty={<EmptyState title="暂无注册用户" />}
                columns={[
                  {
                    id: "user",
                    header: "用户",
                    cell: (u) => (
                      <>
                        <div className="font-medium">{u.username}</div>
                        <div className="text-[11px] text-muted-foreground">{u.email}</div>
                      </>
                    ),
                  },
                  {
                    id: "role",
                    header: "角色",
                    cell: (u) => (
                      <Badge status={u.role === "ADMIN" ? "info" : "neutral"}>
                        {labelRole(u.role)}
                      </Badge>
                    ),
                  },
                  {
                    id: "plan",
                    header: "会员",
                    cell: (u) => <Badge status="vip">{labelPlan(u.planCode)}</Badge>,
                  },
                  {
                    id: "balance",
                    header: "积分余额",
                    align: "end",
                    className: "font-mono tabular-nums",
                    cell: (u) => Number(u.balanceCredits ?? 0).toFixed(2),
                  },
                ]}
              />
            </Card>

            <Card className="p-4">
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-sm font-semibold">最近操作记录</h2>
                <Link href="/prompts#audit-logs" className="text-xs text-primary hover:underline">
                  全部操作记录 →
                </Link>
              </div>
              {audits.length === 0 ? (
                <EmptyState title="暂无操作记录" />
              ) : (
                <ul className="space-y-2 text-xs">
                  {audits.map((log) => (
                    <li
                      key={log.id}
                      className="flex items-center justify-between gap-3 rounded-lg border border-border/70 bg-background/50 px-3 py-2"
                    >
                      <div className="flex min-w-0 items-center gap-2">
                        <Badge status="info">{labelAuditAction(log.action)}</Badge>
                        <span className="truncate font-medium">{log.operatorName}</span>
                        <span className="truncate text-[11px] text-muted-foreground">
                          {labelTargetType(log.targetType)}
                        </span>
                      </div>
                      <span className="shrink-0 text-[11px] text-muted-foreground tabular-nums">
                        {log.createdAt ? new Date(log.createdAt).toLocaleString("zh-CN") : ""}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </section>
        </>
      )}
    </AdminShell>
  );
}
