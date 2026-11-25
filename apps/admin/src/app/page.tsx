"use client";

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
    Promise.all([
      adminOverviewApi.overview(),
      adminOverviewApi.listUsers(),
      adminAuditApi.list(15),
    ])
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

  const kpis = stats
    ? [
        { label: "注册用户", value: stats.totalUsers, tone: "text-foreground" },
        { label: "作品总数", value: stats.totalBooks, tone: "text-foreground" },
        { label: "Agent 运行总数", value: stats.totalRuns, tone: "text-blue-400" },
        {
          label: "失败 Outbox 事件",
          value: stats.failedOutboxEvents,
          tone: stats.failedOutboxEvents > 0 ? "text-rose-400" : "text-emerald-400",
        },
        { label: "排队/处理中文档", value: stats.pendingDocuments, tone: "text-amber-300" },
        { label: "蝴蝶效应任务", value: stats.totalRippleJobs, tone: "text-sky-400" },
        { label: "待审记忆提案", value: stats.pendingProposals, tone: "text-violet-300" },
        {
          label: "安全护栏拦截",
          value: stats.guardrailBlocksCount,
          tone: stats.guardrailBlocksCount > 0 ? "text-amber-400" : "text-emerald-400",
        },
      ]
    : [];

  return (
    <AdminShell
      title="运营总览大盘"
      subtitle="全局用户、作品、异步任务健康度与安全护栏实时快照"
      actions={
        <button
          type="button"
          onClick={() => void loadAll()}
          className="rounded-md border border-border bg-card px-3 py-1.5 text-xs hover:bg-muted"
        >
          刷新大盘
        </button>
      }
    >
      {error && (
        <div className="rounded-lg border border-rose-500/40 bg-rose-500/10 px-4 py-2.5 text-xs text-rose-300">
          {error}
        </div>
      )}

      {loading ? (
        <div className="py-10 text-center text-xs text-muted-foreground">正在拉取全局指标...</div>
      ) : (
        <>
          <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {kpis.map((item) => (
              <div key={item.label} className="rounded-xl border border-border bg-card p-4">
                <div className="text-xs text-muted-foreground">{item.label}</div>
                <div className={`mt-2 font-mono text-2xl font-bold ${item.tone}`}>
                  {item.value.toLocaleString()}
                </div>
              </div>
            ))}
          </section>

          <section className="grid gap-6 lg:grid-cols-2">
            <div className="rounded-xl border border-border bg-card p-4">
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-sm font-semibold">最近注册用户 ({users.length})</h2>
                <Link href="/billing" className="text-xs text-blue-400 hover:underline">
                  VIP 授信管理 →
                </Link>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs">
                  <thead className="border-b border-border text-[11px] text-muted-foreground">
                    <tr>
                      <th className="py-2">用户</th>
                      <th className="py-2">角色</th>
                      <th className="py-2">VIP</th>
                      <th className="py-2 text-right">余额</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {users.slice(0, 12).map((u) => (
                      <tr key={u.id}>
                        <td className="py-2">
                          <div className="font-medium">{u.username}</div>
                          <div className="font-mono text-[10px] text-muted-foreground">{u.email}</div>
                        </td>
                        <td className="py-2 font-mono text-[11px]">{u.role}</td>
                        <td className="py-2 font-mono text-[11px] text-amber-300">{u.planCode}</td>
                        <td className="py-2 text-right font-mono">
                          {Number(u.balanceCredits ?? 0).toFixed(2)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="rounded-xl border border-border bg-card p-4">
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-sm font-semibold">最近后台审计流水</h2>
                <Link href="/prompts" className="text-xs text-blue-400 hover:underline">
                  查看全部审计 →
                </Link>
              </div>
              {audits.length === 0 ? (
                <p className="py-6 text-center text-xs text-muted-foreground">暂无管理操作审计记录</p>
              ) : (
                <ul className="space-y-2 text-xs">
                  {audits.map((log) => (
                    <li
                      key={log.id}
                      className="flex items-center justify-between gap-3 rounded-lg border border-border/70 bg-background/50 px-3 py-2"
                    >
                      <div className="min-w-0">
                        <span className="rounded bg-blue-500/20 px-1.5 py-0.5 font-mono text-[10px] text-blue-300">
                          {log.action}
                        </span>
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
            </div>
          </section>
        </>
      )}
    </AdminShell>
  );
}
