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
  adminAuthApi,
  adminUsersApi,
  type AdminUserBook,
  type AdminUserRow,
} from "@/lib/admin-api";
import { labelGenreCodes, labelPlan, labelRole } from "@/lib/admin-labels";
import { formatAdminError } from "@/lib/error-messages";

export default function AdminUsersPage() {
  const [users, setUsers] = useState<AdminUserRow[]>([]);
  const [currentUserId, setCurrentUserId] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [roleFilter, setRoleFilter] = useState("");
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const toast = useToast();
  const confirm = useConfirm();
  const [error, setError] = useState<string | null>(null);

  const [creditTarget, setCreditTarget] = useState<AdminUserRow | null>(null);
  const [creditAmount, setCreditAmount] = useState("100");
  const [creditReason, setCreditReason] = useState("后台扶持赠与");

  const [inspectUser, setInspectUser] = useState<AdminUserRow | null>(null);
  const [inspectBooks, setInspectBooks] = useState<AdminUserBook[]>([]);

  const loadUsers = useCallback(async (q?: string, role?: string) => {
    setError(null);
    try {
      const list = await adminUsersApi.list(q, role, 100);
      setUsers(list);
    } catch (err) {
      setError(formatAdminError(err, "加载用户列表失败，请稍后重试"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let active = true;
    adminAuthApi
      .me()
      .then((me) => {
        if (active) setCurrentUserId(me.id);
      })
      .catch(() => {
        /* AdminShell handles auth redirect */
      });
    return () => {
      active = false;
    };
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
        setError(formatAdminError(err, "加载用户列表失败，请稍后重试"));
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
    const nextLabel = labelRole(nextRole);
    await confirm({
      title: nextRole === "ADMIN" ? `将 ${u.username} 设为管理员` : `将 ${u.username} 降为作者`,
      description:
        nextRole === "ADMIN"
          ? `设为管理员后，${u.username}（${u.email}）将拥有管理后台的全部权限。`
          : `降为作者后，${u.username}（${u.email}）将无法继续访问管理后台。`,
      confirmLabel: `设为${nextLabel}`,
      variant: nextRole === "AUTHOR" ? "destructive" : "default",
      onConfirm: async () => {
        setBusyId(u.id);
        try {
          await adminUsersApi.updateRole(u.id, nextRole);
          toast.success(`已将 ${u.username} 设为${nextLabel}`);
          await loadUsers(query, roleFilter);
        } catch (err) {
          throw new Error(formatAdminError(err, "修改角色失败，请稍后重试"));
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
        `已向 ${creditTarget.username} 发放 ${amt} 积分，当前余额 ${Number(res.balanceCredits ?? 0).toFixed(2)}`,
      );
      setCreditTarget(null);
      await loadUsers(query, roleFilter);
    } catch (err) {
      toast.error(formatAdminError(err, "发放积分失败，请稍后重试"));
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
      toast.error(formatAdminError(err, "加载作品列表失败，请稍后重试"));
    }
  }

  const hasFilter = Boolean(query.trim() || roleFilter);

  return (
    <AdminShell
      title="用户与权限"
      actions={
        <Button
          type="button"
          onClick={() => void loadUsers(query, roleFilter)}
          variant="outline"
          size="sm"
        >
          刷新
        </Button>
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
              onClick={() => void loadUsers(query, roleFilter)}
            >
              重新加载
            </Button>
          }
        >
          {error}
        </Alert>
      )}

      {creditTarget && (
        <Card className="p-4">
          <form onSubmit={handleGrantCredits} className="grid gap-3 sm:grid-cols-4">
            <div className="sm:col-span-4 text-xs font-semibold">
              发放积分 · {creditTarget.username}（{creditTarget.email}）
            </div>
            <div className="space-y-1">
              <label htmlFor="creditAmount" className="text-[11px] text-muted-foreground">
                数量（积分）
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
                发放原因
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
                loading={busyId === creditTarget.id}
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
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-sm font-semibold">用户列表 ({users.length})</h2>
          <div className="flex flex-wrap items-center gap-2">
            <Input
              aria-label="搜索姓名或邮箱"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="搜索姓名或邮箱"
              className="w-52"
            />
            <Select
              id="roleFilter"
              label="角色筛选"
              hideLabel
              value={roleFilter}
              onValueChange={(value) => value !== null && setRoleFilter(value)}
              options={[
                { value: "", label: "全部角色" },
                { value: "ADMIN", label: "管理员" },
                { value: "AUTHOR", label: "作者" },
              ]}
            />
          </div>
        </div>

        <DataTable
          caption="用户列表"
          rows={users}
          rowKey={(u) => u.id}
          loading={loading}
          empty={
            hasFilter ? (
              <EmptyState
                title="没有符合条件的用户"
                action={
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setQuery("");
                      setRoleFilter("");
                    }}
                  >
                    清除筛选
                  </Button>
                }
              />
            ) : (
              <EmptyState title="暂无用户" />
            )
          }
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
                <Badge status={u.role === "ADMIN" ? "info" : "neutral"}>{labelRole(u.role)}</Badge>
              ),
            },
            {
              id: "plan",
              header: "会员",
              cell: (u) => <Badge status="vip">{labelPlan(u.planCode)}</Badge>,
            },
            {
              id: "books",
              header: "作品数",
              align: "end",
              className: "font-mono tabular-nums",
              cell: (u) => u.bookCount ?? 0,
            },
            {
              id: "balance",
              header: "积分余额",
              align: "end",
              className: "font-mono tabular-nums",
              cell: (u) => Number(u.balanceCredits ?? 0).toFixed(2),
            },
            {
              id: "actions",
              header: "操作",
              align: "end",
              cell: (u) => {
                const isSelf = currentUserId !== null && u.id === currentUserId;
                return (
                  <div className="flex justify-end gap-1.5">
                    <Button
                      type="button"
                      onClick={() => void handleInspectBooks(u)}
                      variant="outline"
                      size="sm"
                    >
                      详情
                    </Button>
                    <Button
                      type="button"
                      onClick={() => setCreditTarget(u)}
                      variant="outline"
                      size="sm"
                    >
                      发积分
                    </Button>
                    <Button
                      type="button"
                      disabled={busyId === u.id || isSelf}
                      title={isSelf ? "当前登录账号不可变更自身权限" : undefined}
                      onClick={() => void handleToggleRole(u)}
                      variant="outline"
                      size="sm"
                    >
                      {isSelf ? "当前账号" : u.role === "ADMIN" ? "降为作者" : "设为管理员"}
                    </Button>
                  </div>
                );
              },
            },
          ]}
        />
      </Card>

      {inspectUser && (
        <Card className="p-4">
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 className="text-sm font-semibold">
                {inspectUser.username} · 作品列表 ({inspectBooks.length})
              </h2>
              <div className="mt-0.5 font-mono text-[11px] text-muted-foreground">
                用户编号：{inspectUser.id}
              </div>
            </div>
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
                  <div className="mt-1.5 flex items-center justify-between text-[11px] text-muted-foreground">
                    <span>{b.genreCode ? labelGenreCodes(b.genreCode) : "默认题材"}</span>
                    <span className="font-mono tabular-nums">
                      {b.chapterCount} 章 · {b.totalWordCount.toLocaleString()} 字
                    </span>
                  </div>
                  <div className="mt-1 truncate font-mono text-[10px] text-muted-foreground">
                    作品编号：{b.id}
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
