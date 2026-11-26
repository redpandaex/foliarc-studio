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
  AdminApiError,
  adminAuditApi,
  adminOverviewApi,
  type AdminAuditLogRow,
  type AdminOverviewStats,
  type AdminUserRow,
} from "@/lib/admin-api";

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
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载总览失败");
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
        setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载总览失败");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const kpis: { label: string; value: number; status: UiStatus }[] = stats
    ? [
        { label: "注册用户", value: stats.totalUsers, status: "neutral" },
        { label: "作品总数", value: stats.totalBooks, status: "neutral" },
        { label: "Agent 运行总数", value: stats.totalRuns, status: "info" },
        {
          label: "失败 Outbox 事件",
          value: stats.failedOutboxEvents,
          status: stats.failedOutboxEvents > 0 ? "danger" : "success",
        },
        { label: "排队/处理中文档", value: stats.pendingDocuments, status: "warning" },
        { label: "蝴蝶效应任务", value: stats.totalRippleJobs, status: "info" },
        { label: "待审记忆提案", value: stats.pendingProposals, status: "ai" },
        {
          label: "安全护栏拦截",
          value: stats.guardrailBlocksCount,
          status: stats.guardrailBlocksCount > 0 ? "warning" : "success",
        },
      ]
    : [];

  return (
    <AdminShell
      title="运营总览大盘"
      subtitle="全局用户、作品、异步任务健康度与安全护栏实时快照"
      actions={
        <Button type="button" onClick={() => void loadAll()} variant="outline" size="sm">
          刷新大盘
        </Button>
      }
    >
      {error && (
        <Alert status="danger" announce="assertive">
          {error}
        </Alert>
      )}

      {loading ? (
        <div className="py-10 text-center text-xs text-muted-foreground">正在拉取全局指标...</div>
      ) : (
        <>
          <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {kpis.map((item) => (
              <KpiCard
                key={item.label}
                label={item.label}
                value={item.value.toLocaleString()}
                status={item.status}
              />
            ))}
          </section>

          <section className="grid gap-6 lg:grid-cols-2">
            <Card className="p-4">
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-sm font-semibold">最近注册用户 ({users.length})</h2>
                <Link href="/billing" className="text-xs text-primary hover:underline">
                  VIP 授信管理 →
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
                        <div className="font-mono text-[10px] text-muted-foreground">{u.email}</div>
                      </>
                    ),
                  },
                  {
                    id: "role",
                    header: "角色",
                    className: "font-mono text-[11px]",
                    cell: (u) => (
                      <Badge status={u.role === "ADMIN" ? "info" : "neutral"}>{u.role}</Badge>
                    ),
                  },
                  {
                    id: "plan",
                    header: "VIP",
                    className: "font-mono text-[11px] text-status-warning",
                    cell: (u) => <Badge status="vip">{u.planCode}</Badge>,
                  },
                  {
                    id: "balance",
                    header: "余额",
                    align: "end",
                    className: "font-mono",
                    cell: (u) => Number(u.balanceCredits ?? 0).toFixed(2),
                  },
                ]}
              />
            </Card>

            <Card className="p-4">
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-sm font-semibold">最近后台审计流水</h2>
                <Link href="/prompts" className="text-xs text-primary hover:underline">
                  查看全部审计 →
                </Link>
              </div>
              {audits.length === 0 ? (
                <EmptyState title="暂无管理操作审计记录" />
              ) : (
                <ul className="space-y-2 text-xs">
                  {audits.map((log) => (
                    <li
                      key={log.id}
                      className="flex items-center justify-between gap-3 rounded-lg border border-border/70 bg-background/50 px-3 py-2"
                    >
                      <div className="min-w-0">
                        <Badge status="info">{log.action}</Badge>
                        <span className="ml-2 font-medium">{log.operatorName}</span>
                        <span className="ml-2 font-mono text-[10px] text-muted-foreground">
                          {log.targetType}:{log.targetId.slice(0, 14)}
                        </span>
                      </div>
                      <span className="shrink-0 font-mono text-[10px] text-muted-foreground">
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
