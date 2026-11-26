"use client";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { DataTable } from "@/components/ui/data-table";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { useToast } from "@/components/ui/toast";

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
  const toast = useToast();
  const confirm = useConfirm();
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
        setError(
          err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载用户列表失败",
        );
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
    await confirm({
      title: "切换用户角色",
      description: `确认将用户「${u.username} (${u.email})」的角色切换为 ${nextRole}？操作将写入审计日志。`,
      confirmLabel: `切换为 ${nextRole}`,
      variant: nextRole === "AUTHOR" ? "destructive" : "default",
      onConfirm: async () => {
        setBusyId(u.id);
        try {
          await adminUsersApi.updateRole(u.id, nextRole);
          toast.success(`已将 ${u.email} 的角色更新为 ${nextRole}。`);
          await loadUsers(query, roleFilter);
        } catch (err) {
          throw new Error(
            err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "修改角色失败",
          );
        } finally {
          setBusyId(null);
        }
      },
    });
  }

  async function handleGrantCredits(e: FormEvent) {
    e.preventDefault();
    if (!creditTarget) return;
    const amt = Number.parseFloat(creditAmount);
    if (Number.isNaN(amt) || amt <= 0) return;
    setBusyId(creditTarget.id);
    try {
      const res = await adminUsersApi.grantCredits(
        creditTarget.id,
        amt,
        creditReason.trim() || "后台人工赠与",
      );
      toast.success(
        `已为 ${creditTarget.email} 发放 +${amt} Credits（最新余额：${Number(res.balanceCredits ?? 0).toFixed(2)}）。`,
      );
      setCreditTarget(null);
      await loadUsers(query, roleFilter);
    } catch (err) {
      toast.error(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "发放积分失败");
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
      toast.error(
        err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载用户作品失败",
      );
    }
  }

  return (
    <AdminShell
      title="用户与角色权限治理"
      subtitle="按邮箱/用户名检索用户、切换 ADMIN/AUTHOR 角色（防自降级）、人工发放创作积分与查看作品列表"
      actions={
        <div className="flex items-center gap-2">
          <Input
            aria-label="搜索邮箱或用户名"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索邮箱或用户名..."
            className="w-48"
          />
          <Select
            id="roleFilter"
            label="角色筛选"
            hideLabel
            value={roleFilter}
            onValueChange={(value) => value !== null && setRoleFilter(value)}
            options={[
              { value: "", label: "全部角色" },
              { value: "ADMIN", label: "ADMIN" },
              { value: "AUTHOR", label: "AUTHOR" },
            ]}
          />
          <Button
            type="button"
            onClick={() => void loadUsers(query, roleFilter)}
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

      {creditTarget && (
        <Card className="p-4">
          <form onSubmit={handleGrantCredits} className="grid gap-3 sm:grid-cols-4">
            <div className="sm:col-span-4 text-xs font-semibold">
              人工发放创作积分：{creditTarget.username} ({creditTarget.email})
            </div>
            <div className="space-y-1">
              <label htmlFor="creditAmount" className="text-[11px] text-muted-foreground">
                发放额度 (Credits)
              </label>
              <Input
                id="creditAmount"
                type="number"
                min={0.01}
                step="1"
                required
                value={creditAmount}
                onChange={(e) => setCreditAmount(e.target.value)}
                className="font-mono"
              />
            </div>
            <div className="space-y-1 sm:col-span-2">
              <label htmlFor="creditReason" className="text-[11px] text-muted-foreground">
                发放原因（记录审计）
              </label>
              <Input
                id="creditReason"
                value={creditReason}
                onChange={(e) => setCreditReason(e.target.value)}
              />
            </div>
            <div className="flex items-end justify-end gap-2">
              <Button
                type="button"
                onClick={() => setCreditTarget(null)}
                variant="outline"
                size="sm"
              >
                取消
              </Button>
              <Button
                type="submit"
                disabled={busyId === creditTarget.id}
                variant="default"
                size="sm"
              >
                确认发放
              </Button>
            </div>
          </form>
        </Card>
      )}

      <Card className="p-4">
        <h2 className="mb-3 text-sm font-semibold">平台用户列表 ({users.length})</h2>
        <DataTable
          caption="平台用户列表"
          rows={users}
          rowKey={(u) => u.id}
          loading={loading}
          empty={<EmptyState title="无匹配用户" />}
          columns={[
            {
              id: "user",
              header: "用户 / UUID",
              cell: (u) => (
                <>
                  <div className="font-medium">
                    {u.username}{" "}
                    <span className="font-normal text-muted-foreground">({u.email})</span>
                  </div>
                  <div className="font-mono text-[10px] text-muted-foreground">{u.id}</div>
                </>
              ),
            },
            {
              id: "role",
              header: "角色",
              cell: (u) => <Badge status={u.role === "ADMIN" ? "info" : "neutral"}>{u.role}</Badge>,
            },
            {
              id: "plan",
              header: "VIP 档位",
              className: "font-mono text-[11px] text-status-warning",
              cell: (u) => <Badge status="vip">{u.planCode}</Badge>,
            },
            {
              id: "books",
              header: "作品数",
              align: "end",
              className: "font-mono",
              cell: (u) => u.bookCount ?? 0,
            },
            {
              id: "balance",
              header: "积分余额",
              align: "end",
              className: "font-mono",
              cell: (u) => Number(u.balanceCredits ?? 0).toFixed(2),
            },
            {
              id: "actions",
              header: "治理操作",
              align: "end",
              cell: (u) => (
                <div className="flex justify-end gap-1.5">
                  <Button
                    type="button"
                    onClick={() => void handleInspectBooks(u)}
                    variant="outline"
                    size="sm"
                  >
                    作品
                  </Button>
                  <Button
                    type="button"
                    onClick={() => setCreditTarget(u)}
                    variant="outline"
                    size="sm"
                  >
                    加积分
                  </Button>
                  <Button
                    type="button"
                    disabled={busyId === u.id}
                    onClick={() => void handleToggleRole(u)}
                    variant="outline"
                    size="sm"
                  >
                    {u.role === "ADMIN" ? "降为作者" : "设为管理员"}
                  </Button>
                </div>
              ),
            },
          ]}
        />
      </Card>

      {inspectUser && (
        <Card className="p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold">
              {inspectUser.username} 的作品清单 ({inspectBooks.length})
            </h2>
            <Button type="button" onClick={() => setInspectUser(null)} variant="ghost" size="sm">
              关闭
            </Button>
          </div>
          {inspectBooks.length === 0 ? (
            <EmptyState title="该用户暂无作品" />
          ) : (
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              {inspectBooks.map((b) => (
                <Card key={b.id} className="p-3 text-xs">
                  <div className="font-semibold">{b.title}</div>
                  <div className="mt-1 font-mono text-[10px] text-muted-foreground">{b.id}</div>
                  <div className="mt-1.5 flex items-center justify-between font-mono text-[11px] text-muted-foreground">
                    <span>{b.genreCode || "默认"}</span>
                    <span>
                      {b.chapterCount} 章 · {b.totalWordCount.toLocaleString()} 字
                    </span>
                  </div>
                </Card>
              ))}
            </div>
          )}
        </Card>
      )}
    </AdminShell>
  );
}
