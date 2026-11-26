"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { AdminShell } from "@/components/admin-shell";
import {
  AdminApiError,
  adminUsersApi,
  type AdminUserBook,
  type AdminUserRow,
} from "@/lib/admin-api";

export default function AdminUsersPage() {
  const [users, setUsers] = useState<AdminUserRow[]>([]);
  const [query, setQuery] = useState("");
  const [roleFilter, setRoleFilter] = useState("");
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [creditTarget, setCreditTarget] = useState<AdminUserRow | null>(null);
  const [creditAmount, setCreditAmount] = useState("100");
  const [creditReason, setCreditReason] = useState("后台人工扶持赠与");

  const [inspectUser, setInspectUser] = useState<AdminUserRow | null>(null);
  const [inspectBooks, setInspectBooks] = useState<AdminUserBook[]>([]);

  const loadUsers = useCallback(async (q?: string, role?: string) => {
    setError(null);
    try {
      const list = await adminUsersApi.list(q, role, 100);
      setUsers(list);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载用户列表失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let active = true;
    adminUsersApi
      .list(query, roleFilter, 100)
      .then((list) => {
        if (!active) return;
        setUsers(list);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载用户列表失败");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [query, roleFilter]);

  async function handleToggleRole(u: AdminUserRow) {
    const nextRole: "ADMIN" | "AUTHOR" = u.role === "ADMIN" ? "AUTHOR" : "ADMIN";
    if (
      !window.confirm(
        `确认将用户「${u.username} (${u.email})」的角色切换为 ${nextRole}？操作将写入审计日志。`,
      )
    ) {
      return;
    }
    setBusyId(u.id);
    setError(null);
    setNotice(null);
    try {
      await adminUsersApi.updateRole(u.id, nextRole);
      setNotice(`已将 ${u.email} 的角色更新为 ${nextRole}。`);
      await loadUsers(query, roleFilter);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "修改角色失败");
    } finally {
      setBusyId(null);
    }
  }

  async function handleGrantCredits(e: FormEvent) {
    e.preventDefault();
    if (!creditTarget) return;
    const amt = Number.parseFloat(creditAmount);
    if (Number.isNaN(amt) || amt <= 0) return;
    setBusyId(creditTarget.id);
    setError(null);
    setNotice(null);
    try {
      const res = await adminUsersApi.grantCredits(
        creditTarget.id,
        amt,
        creditReason.trim() || "后台人工赠与",
      );
      setNotice(
        `已为 ${creditTarget.email} 发放 +${amt} Credits（最新余额：${Number(res.balanceCredits ?? 0).toFixed(2)}）。`,
      );
      setCreditTarget(null);
      await loadUsers(query, roleFilter);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "发放积分失败");
    } finally {
      setBusyId(null);
    }
  }

  async function handleInspectBooks(u: AdminUserRow) {
    setInspectUser(u);
    setInspectBooks([]);
    try {
      const list = await adminUsersApi.listBooks(u.id);
      setInspectBooks(list);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载用户作品失败");
    }
  }

  return (
    <AdminShell
      title="用户与角色权限治理"
      subtitle="按邮箱/用户名检索用户、切换 ADMIN/AUTHOR 角色（防自降级）、人工发放创作积分与查看作品列表"
      actions={
        <div className="flex items-center gap-2">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索邮箱或用户名..."
            className="w-48 rounded-md border border-border bg-card px-2.5 py-1.5 text-xs outline-none focus:border-blue-500"
          />
          <select
            value={roleFilter}
            onChange={(e) => setRoleFilter(e.target.value)}
            className="rounded-md border border-border bg-card px-2.5 py-1.5 text-xs"
          >
            <option value="">全部角色</option>
            <option value="ADMIN">ADMIN</option>
            <option value="AUTHOR">AUTHOR</option>
          </select>
          <button
            type="button"
            onClick={() => void loadUsers(query, roleFilter)}
            className="rounded-md border border-border bg-card px-3 py-1.5 text-xs hover:bg-muted"
          >
            刷新
          </button>
        </div>
      }
    >
      {notice && (
        <div className="rounded-lg border border-emerald-500/40 bg-emerald-500/10 px-4 py-2 text-xs text-emerald-300">
          {notice}
        </div>
      )}
      {error && (
        <div className="rounded-lg border border-rose-500/40 bg-rose-500/10 px-4 py-2 text-xs text-rose-300">
          {error}
        </div>
      )}

      {creditTarget && (
        <form
          onSubmit={handleGrantCredits}
          className="grid gap-3 rounded-xl border border-blue-500/40 bg-blue-500/5 p-4 sm:grid-cols-4"
        >
          <div className="sm:col-span-4 text-xs font-semibold">
            人工发放创作积分：{creditTarget.username} ({creditTarget.email})
          </div>
          <div className="space-y-1">
            <label className="text-[11px] text-muted-foreground">发放额度 (Credits)</label>
            <input
              type="number"
              min={0.01}
              step="1"
              required
              value={creditAmount}
              onChange={(e) => setCreditAmount(e.target.value)}
              className="w-full rounded border border-border bg-background px-2.5 py-1.5 font-mono text-xs"
            />
          </div>
          <div className="space-y-1 sm:col-span-2">
            <label className="text-[11px] text-muted-foreground">发放原因（记录审计）</label>
            <input
              value={creditReason}
              onChange={(e) => setCreditReason(e.target.value)}
              className="w-full rounded border border-border bg-background px-2.5 py-1.5 text-xs"
            />
          </div>
          <div className="flex items-end justify-end gap-2">
            <button
              type="button"
              onClick={() => setCreditTarget(null)}
              className="rounded border border-border px-3 py-1.5 text-xs hover:bg-muted"
            >
              取消
            </button>
            <button
              type="submit"
              disabled={busyId === creditTarget.id}
              className="rounded bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-500"
            >
              确认发放
            </button>
          </div>
        </form>
      )}

      <section className="rounded-xl border border-border bg-card p-4">
        <h2 className="mb-3 text-sm font-semibold">平台用户列表 ({users.length})</h2>
        {loading ? (
          <p className="py-8 text-center text-xs text-muted-foreground">加载中...</p>
        ) : users.length === 0 ? (
          <p className="py-8 text-center text-xs text-muted-foreground">无匹配用户</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="border-b border-border text-[11px] text-muted-foreground">
                <tr>
                  <th className="py-2">用户 / UUID</th>
                  <th className="py-2">角色</th>
                  <th className="py-2">VIP 档位</th>
                  <th className="py-2 text-right">作品数</th>
                  <th className="py-2 text-right">积分余额</th>
                  <th className="py-2 text-right">治理操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {users.map((u) => (
                  <tr key={u.id}>
                    <td className="py-2">
                      <div className="font-medium">
                        {u.username}{" "}
                        <span className="font-normal text-muted-foreground">({u.email})</span>
                      </div>
                      <div className="font-mono text-[10px] text-muted-foreground">{u.id}</div>
                    </td>
                    <td className="py-2">
                      <span
                        className={`rounded px-1.5 py-0.5 font-mono text-[10px] font-semibold ${
                          u.role === "ADMIN"
                            ? "bg-blue-500/20 text-blue-300"
                            : "bg-muted text-muted-foreground"
                        }`}
                      >
                        {u.role}
                      </span>
                    </td>
                    <td className="py-2 font-mono text-[11px] text-amber-300">{u.planCode}</td>
                    <td className="py-2 text-right font-mono">{u.bookCount ?? 0}</td>
                    <td className="py-2 text-right font-mono">
                      {Number(u.balanceCredits ?? 0).toFixed(2)}
                    </td>
                    <td className="py-2 text-right">
                      <div className="flex justify-end gap-1.5">
                        <button
                          type="button"
                          onClick={() => void handleInspectBooks(u)}
                          className="rounded border border-border px-2 py-0.5 text-[11px] hover:bg-muted"
                        >
                          作品
                        </button>
                        <button
                          type="button"
                          onClick={() => setCreditTarget(u)}
                          className="rounded border border-emerald-500/40 px-2 py-0.5 text-[11px] text-emerald-300 hover:bg-emerald-500/10"
                        >
                          加积分
                        </button>
                        <button
                          type="button"
                          disabled={busyId === u.id}
                          onClick={() => void handleToggleRole(u)}
                          className="rounded border border-blue-500/40 px-2 py-0.5 text-[11px] text-blue-300 hover:bg-blue-500/10 disabled:opacity-50"
                        >
                          {u.role === "ADMIN" ? "降为作者" : "设为管理员"}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {inspectUser && (
        <section className="rounded-xl border border-border bg-card p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold">
              {inspectUser.username} 的作品清单 ({inspectBooks.length})
            </h2>
            <button
              type="button"
              onClick={() => setInspectUser(null)}
              className="text-xs text-muted-foreground hover:text-foreground"
            >
              关闭
            </button>
          </div>
          {inspectBooks.length === 0 ? (
            <p className="py-4 text-center text-xs text-muted-foreground">该用户暂无作品</p>
          ) : (
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              {inspectBooks.map((b) => (
                <div
                  key={b.id}
                  className="rounded-lg border border-border/80 bg-background/50 p-3 text-xs"
                >
                  <div className="font-semibold">{b.title}</div>
                  <div className="mt-1 font-mono text-[10px] text-muted-foreground">
                    {b.id}
                  </div>
                  <div className="mt-1.5 flex items-center justify-between font-mono text-[11px] text-muted-foreground">
                    <span>{b.genreCode || "默认"}</span>
                    <span>
                      {b.chapterCount} 章 · {b.totalWordCount.toLocaleString()} 字
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      )}
    </AdminShell>
  );
}
