"use client";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { DataTable } from "@/components/ui/data-table";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { KpiCard } from "@/components/ui/kpi-card";
import { Select } from "@/components/ui/select";
import { useToast } from "@/components/ui/toast";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { AdminShell } from "@/components/admin-shell";
import {
  adminBillingApi,
  adminUsersApi,
  type AdminBillingOverview,
  type AdminUserRow,
  type VipPlanItem,
} from "@/lib/admin-api";
import {
  bpsToZheInput,
  formatBpsAsDiscount,
  labelLedgerType,
  zheInputToBps,
} from "@/lib/admin-labels";
import { formatAdminError } from "@/lib/error-messages";

export default function AdminBillingPage() {
  const [overview, setOverview] = useState<AdminBillingOverview | null>(null);
  const [plans, setPlans] = useState<VipPlanItem[]>([]);
  const [users, setUsers] = useState<AdminUserRow[]>([]);
  const [editingPlan, setEditingPlan] = useState<VipPlanItem | null>(null);
  const [editPriceCredits, setEditPriceCredits] = useState("");
  const [editGrantCredits, setEditGrantCredits] = useState("");
  const [editDiscountZhe, setEditDiscountZhe] = useState("10");
  const [editRippleRange, setEditRippleRange] = useState("");

  const [grantUserId, setGrantUserId] = useState("");
  const [manualUserId, setManualUserId] = useState(false);
  const [grantPlanCode, setGrantPlanCode] = useState("SVIP");
  const [grantDays, setGrantDays] = useState("30");
  const [grantBonus, setGrantBonus] = useState(true);

  const toast = useToast();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const loadData = useCallback(async () => {
    setError(null);
    try {
      const [ov, pl] = await Promise.all([
        adminBillingApi.overview(),
        adminBillingApi.listVipPlans(),
      ]);
      setOverview(ov);
      setPlans(pl);
    } catch (err) {
      setError(formatAdminError(err, "加载计费与会员数据失败，请稍后重试"));
    }
  }, []);

  useEffect(() => {
    let active = true;
    Promise.all([
      adminBillingApi.overview(),
      adminBillingApi.listVipPlans(),
      adminUsersApi.list(undefined, undefined, 100).catch(() => [] as AdminUserRow[]),
    ])
      .then(([ov, pl, us]) => {
        if (!active) return;
        setOverview(ov);
        setPlans(pl);
        setUsers(us);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(formatAdminError(err, "加载计费与会员数据失败，请稍后重试"));
      });
    return () => {
      active = false;
    };
  }, []);

  function openPlanEditor(plan: VipPlanItem) {
    setEditingPlan(plan);
    setEditPriceCredits(String(plan.monthlyPriceCredits));
    setEditGrantCredits(String(plan.monthlyGrantCredits));
    setEditDiscountZhe(bpsToZheInput(plan.creditDiscountBps));
    setEditRippleRange(String(plan.entitlements.rippleMaxRangeChapters));
  }

  async function handleSavePlan(e: FormEvent) {
    e.preventDefault();
    if (!editingPlan) return;
    setBusy(true);
    setError(null);
    try {
      await adminBillingApi.updateVipPlan(editingPlan.planCode, {
        monthlyPriceCredits: Number.parseFloat(editPriceCredits),
        monthlyGrantCredits: Number.parseFloat(editGrantCredits),
        creditDiscountBps: zheInputToBps(editDiscountZhe),
        entitlements: {
          ...editingPlan.entitlements,
          rippleMaxRangeChapters: Number.parseInt(editRippleRange, 10),
        },
      });
      toast.success(`已保存并生效「${editingPlan.displayName}」`);
      setEditingPlan(null);
      await loadData();
    } catch (err) {
      setError(formatAdminError(err, "保存会员方案失败，请稍后重试", { action: "save" }));
    } finally {
      setBusy(false);
    }
  }

  async function handleGrantVip(e: FormEvent) {
    e.preventDefault();
    if (!grantUserId.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await adminBillingApi.grantVip({
        userId: grantUserId.trim(),
        planCode: grantPlanCode,
        durationDays: Number.parseInt(grantDays, 10) || 30,
        grantBonusCredits: grantBonus,
        idempotencyKey: `admin-${grantPlanCode}-${Date.now()}`,
      });
      const targetUser = users.find((u) => u.id === grantUserId.trim());
      const planName = plans.find((p) => p.planCode === grantPlanCode)?.displayName ?? grantPlanCode;
      toast.success(
        `已为 ${targetUser ? targetUser.username : grantUserId.slice(0, 10)} 开通${planName}（${grantDays} 天）`,
      );
      setGrantUserId("");
      await loadData();
    } catch (err) {
      setError(formatAdminError(err, "发放会员失败，请稍后重试", { action: "save" }));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AdminShell
      title="计费与会员"
      actions={
        <Button type="button" onClick={() => void loadData()} variant="outline" size="sm">
          刷新
        </Button>
      }
    >
      {error && (
        <Alert
          status="danger"
          announce="assertive"
          action={
            <Button type="button" variant="outline" size="sm" onClick={() => void loadData()}>
              重新加载
            </Button>
          }
        >
          {error}
        </Alert>
      )}

      {/* Credits Overview */}
      {overview && (
        <section className="space-y-2">
          <h2 className="text-xs font-medium text-muted-foreground">积分概况</h2>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <KpiCard label="计费账户" value={overview.totalAccounts} status="neutral" />
            <KpiCard
              label="积分总余额"
              value={Number(overview.totalBalanceCredits ?? 0).toFixed(2)}
              status="neutral"
            />
            <KpiCard
              label="本月全站消耗"
              value={Number(overview.totalMonthlySpentCredits ?? 0).toFixed(4)}
              status="warning"
            />
            <KpiCard
              label="缓存累计节省"
              value={Number(overview.totalCacheSavedCredits ?? 0).toFixed(4)}
              status="success"
            />
          </div>
        </section>
      )}

      {/* Plans + Manual Grant */}
      <section className="grid gap-6 lg:grid-cols-3">
        <Card className="p-4 lg:col-span-2">
          <h2 className="mb-3 text-sm font-semibold">会员方案</h2>
          <div className="grid gap-3 sm:grid-cols-2">
            {plans.map((p) => (
              <Card key={p.planCode} className="p-3 text-xs">
                <div className="flex items-center justify-between">
                  <span className="font-semibold">{p.displayName}</span>
                  <Button
                    type="button"
                    onClick={() => openPlanEditor(p)}
                    variant="outline"
                    size="sm"
                  >
                    编辑
                  </Button>
                </div>
                <dl className="mt-2.5 grid grid-cols-2 gap-x-3 gap-y-1 text-[11px]">
                  <div className="flex justify-between">
                    <dt className="text-muted-foreground">月费</dt>
                    <dd className="font-mono tabular-nums">{p.monthlyPriceCredits} 积分</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="text-muted-foreground">月赠</dt>
                    <dd className="font-mono tabular-nums">{p.monthlyGrantCredits} 积分</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="text-muted-foreground">创作折扣</dt>
                    <dd className="font-mono">{formatBpsAsDiscount(p.creditDiscountBps)}</dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="text-muted-foreground">资料库</dt>
                    <dd className="font-mono tabular-nums">
                      {p.entitlements.userLibraryMaxDocs} 份
                    </dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="text-muted-foreground">记忆章数</dt>
                    <dd className="font-mono tabular-nums">
                      {p.entitlements.maxMemoryChapters} 章
                    </dd>
                  </div>
                  <div className="flex justify-between">
                    <dt className="text-muted-foreground">跨章上限</dt>
                    <dd className="font-mono tabular-nums">
                      {p.entitlements.rippleMaxRangeChapters} 章
                    </dd>
                  </div>
                </dl>
              </Card>
            ))}
          </div>

          {editingPlan && (
            <Card className="mt-4 p-4">
              <form onSubmit={handleSavePlan} className="grid gap-3 sm:grid-cols-4">
                <div className="sm:col-span-4 text-xs font-semibold">
                  编辑方案 · {editingPlan.displayName}
                </div>
                <div className="space-y-1">
                  <label htmlFor="editPriceCredits" className="text-[11px] text-muted-foreground">
                    月费（积分）
                  </label>
                  <Input
                    id="editPriceCredits"
                    type="number"
                    step="0.01"
                    min={0}
                    value={editPriceCredits}
                    onChange={(e) => setEditPriceCredits(e.target.value)}
                    className="font-mono"
                  />
                </div>
                <div className="space-y-1">
                  <label htmlFor="editGrantCredits" className="text-[11px] text-muted-foreground">
                    月赠（积分）
                  </label>
                  <Input
                    id="editGrantCredits"
                    type="number"
                    step="0.01"
                    min={0}
                    value={editGrantCredits}
                    onChange={(e) => setEditGrantCredits(e.target.value)}
                    className="font-mono"
                  />
                </div>
                <div className="space-y-1">
                  <label htmlFor="editDiscountZhe" className="text-[11px] text-muted-foreground">
                    创作折扣（折）
                  </label>
                  <Input
                    id="editDiscountZhe"
                    type="number"
                    step="0.1"
                    min={0.1}
                    max={10}
                    value={editDiscountZhe}
                    onChange={(e) => setEditDiscountZhe(e.target.value)}
                    className="font-mono"
                  />
                </div>
                <div className="space-y-1">
                  <label htmlFor="editRippleRange" className="text-[11px] text-muted-foreground">
                    单次跨章修订上限（章）
                  </label>
                  <Input
                    id="editRippleRange"
                    type="number"
                    min={1}
                    value={editRippleRange}
                    onChange={(e) => setEditRippleRange(e.target.value)}
                    className="font-mono"
                  />
                </div>
                <div className="flex justify-end gap-2 sm:col-span-4">
                  <Button
                    type="button"
                    onClick={() => setEditingPlan(null)}
                    variant="outline"
                    size="sm"
                  >
                    取消
                  </Button>
                  <Button type="submit" loading={busy} variant="default" size="sm">
                    保存并生效
                  </Button>
                </div>
              </form>
            </Card>
          )}
        </Card>

        <Card className="p-4">
          <form onSubmit={handleGrantVip} className="space-y-3">
            <h2 className="text-sm font-semibold">人工发放会员</h2>
            <div className="space-y-1">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <label htmlFor="grantUserId">目标用户</label>
                {users.length > 0 && (
                  <button
                    type="button"
                    onClick={() => setManualUserId((v) => !v)}
                    className="text-[11px] text-primary hover:underline"
                  >
                    {manualUserId ? "从列表选择" : "按编号输入"}
                  </button>
                )}
              </div>
              {users.length > 0 && !manualUserId ? (
                <Select
                  id="grantUserSelect"
                  label="目标用户"
                  hideLabel
                  value={grantUserId}
                  onValueChange={(value) => value !== null && setGrantUserId(value)}
                  options={[
                    { value: "", label: "选择用户" },
                    ...users.map((u) => ({ value: u.id, label: `${u.username}（${u.email}）` })),
                  ]}
                />
              ) : (
                <Input
                  id="grantUserId"
                  required
                  value={grantUserId}
                  onChange={(e) => setGrantUserId(e.target.value)}
                  placeholder="输入用户编号"
                  className="font-mono"
                />
              )}
            </div>

            <div className="grid grid-cols-2 gap-2">
              <div className="space-y-1">
                <Select
                  id="grantPlanCode"
                  label="会员档位"
                  value={grantPlanCode}
                  onValueChange={(value) => value !== null && setGrantPlanCode(value)}
                  options={[
                    { value: "VIP", label: "签约会员" },
                    { value: "SVIP", label: "白金会员" },
                    { value: "STUDIO", label: "工作室版" },
                    { value: "FREE", label: "免费版" },
                  ]}
                />
              </div>
              <div className="space-y-1">
                <label htmlFor="grantDays" className="text-xs text-muted-foreground">
                  天数
                </label>
                <Input
                  id="grantDays"
                  type="number"
                  min={1}
                  value={grantDays}
                  onChange={(e) => setGrantDays(e.target.value)}
                  className="font-mono"
                />
              </div>
            </div>

            <div className="space-y-1">
              <div className="text-xs text-muted-foreground">赠送当月积分</div>
              <div
                role="group"
                aria-label="是否同时赠送当月积分"
                className="inline-flex rounded-lg border border-border bg-muted/40 p-0.5"
              >
                <Button
                  type="button"
                  size="sm"
                  variant={grantBonus ? "default" : "ghost"}
                  aria-pressed={grantBonus}
                  onClick={() => setGrantBonus(true)}
                >
                  同时赠送
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={!grantBonus ? "default" : "ghost"}
                  aria-pressed={!grantBonus}
                  onClick={() => setGrantBonus(false)}
                >
                  不赠送
                </Button>
              </div>
            </div>

            <Button
              type="submit"
              disabled={!grantUserId.trim()}
              loading={busy}
              variant="default"
              size="sm"
              className="w-full"
            >
              确认发放
            </Button>
          </form>
        </Card>
      </section>

      {/* Model Breakdown */}
      {overview && (
        <Card className="p-4">
          <h2 className="mb-3 text-sm font-semibold">分模型消耗</h2>
          <DataTable
            caption="分模型消耗"
            rows={overview.modelBreakdown}
            rowKey={(m) => m.modelAlias}
            empty={<EmptyState title="暂无模型消耗记录" />}
            columns={[
              {
                id: "model",
                header: "模型",
                className: "font-semibold text-primary",
                cell: (m) => m.modelAlias,
              },
              {
                id: "runs",
                header: "调用次数",
                className: "font-mono tabular-nums",
                cell: (m) => m.runsCount,
              },
              {
                id: "input",
                header: "输入 / 缓存 Token",
                className: "font-mono tabular-nums",
                cell: (m) => (
                  <>
                    {m.totalInputTokens.toLocaleString()} / {m.totalCachedTokens.toLocaleString()}
                  </>
                ),
              },
              {
                id: "hitRate",
                header: "缓存命中率",
                className: "font-mono text-status-success tabular-nums",
                cell: (m) => (
                  <>
                    {(m.totalInputTokens > 0
                      ? (m.totalCachedTokens / m.totalInputTokens) * 100
                      : 0
                    ).toFixed(1)}
                    %
                  </>
                ),
              },
              {
                id: "output",
                header: "输出 Token",
                className: "font-mono tabular-nums",
                cell: (m) => m.totalOutputTokens.toLocaleString(),
              },
              {
                id: "spent",
                header: "消耗（积分）",
                align: "end",
                className: "font-mono tabular-nums",
                cell: (m) => Number(m.totalSpentCredits).toFixed(4),
              },
            ]}
          />
        </Card>
      )}

      {/* Recent Ledger */}
      {overview && (
        <Card className="p-4">
          <h2 className="mb-3 text-sm font-semibold">积分流水 ({overview.recentLedger.length})</h2>
          <DataTable
            caption="积分流水"
            rows={overview.recentLedger.slice(0, 25)}
            rowKey={(row) => row.id}
            empty={<EmptyState title="暂无积分流水" />}
            columns={[
              { id: "type", header: "类型", cell: (row) => labelLedgerType(row.entryType) },
              { id: "model", header: "模型", cell: (row) => row.modelAlias ?? "-" },
              {
                id: "description",
                header: "摘要",
                className: "text-muted-foreground",
                cell: (row) => row.description || "-",
              },
              {
                id: "amount",
                header: "变动（积分）",
                align: "end",
                className: "font-mono tabular-nums",
                cell: (row) => Number(row.amountCredits).toFixed(4),
              },
              {
                id: "balance",
                header: "结余（积分）",
                align: "end",
                className: "font-mono tabular-nums",
                cell: (row) => Number(row.balanceAfterCredits).toFixed(4),
              },
            ]}
          />
        </Card>
      )}
    </AdminShell>
  );
}
