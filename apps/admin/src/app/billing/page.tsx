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
  AdminApiError,
  adminBillingApi,
  type AdminBillingOverview,
  type VipPlanItem,
} from "@/lib/admin-api";

export default function AdminBillingPage() {
  const [overview, setOverview] = useState<AdminBillingOverview | null>(null);
  const [plans, setPlans] = useState<VipPlanItem[]>([]);
  const [editingPlan, setEditingPlan] = useState<VipPlanItem | null>(null);
  const [editPriceCredits, setEditPriceCredits] = useState("");
  const [editGrantCredits, setEditGrantCredits] = useState("");
  const [editDiscountBps, setEditDiscountBps] = useState("");
  const [editRippleRange, setEditRippleRange] = useState("");

  const [grantUserId, setGrantUserId] = useState("");
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
      setError(
        err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载计费与 VIP 失败",
      );
    }
  }, []);

  useEffect(() => {
    let active = true;
    Promise.all([adminBillingApi.overview(), adminBillingApi.listVipPlans()])
      .then(([ov, pl]) => {
        if (!active) return;
        setOverview(ov);
        setPlans(pl);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(
          err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载计费与 VIP 失败",
        );
      });
    return () => {
      active = false;
    };
  }, []);

  function openPlanEditor(plan: VipPlanItem) {
    setEditingPlan(plan);
    setEditPriceCredits(String(plan.monthlyPriceCredits));
    setEditGrantCredits(String(plan.monthlyGrantCredits));
    setEditDiscountBps(String(plan.creditDiscountBps));
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
        creditDiscountBps: Number.parseInt(editDiscountBps, 10),
        entitlements: {
          ...editingPlan.entitlements,
          rippleMaxRangeChapters: Number.parseInt(editRippleRange, 10),
        },
      });
      toast.success(`套餐 ${editingPlan.planCode} 已热更新生效并记录审计日志。`);
      setEditingPlan(null);
      await loadData();
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "更新套餐失败");
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
      toast.success(
        `已为用户 ${grantUserId.slice(0, 10)} 授予 ${grantPlanCode} (${grantDays} 天)。`,
      );
      setGrantUserId("");
      await loadData();
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "人工授信 VIP 失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <AdminShell
      title="计费账本 & VIP 权益运营台"
      subtitle="全平台积分核算、KV Cache 省费统计、四档 VIP 套餐热更新与人工授信"
      actions={
        <Button type="button" onClick={() => void loadData()} variant="outline" size="sm">
          刷新数据
        </Button>
      }
    >
      {error && (
        <Alert status="danger" announce="assertive">
          {error}
        </Alert>
      )}

      {overview && (
        <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <KpiCard label="计费账户总数" value={overview.totalAccounts} status="neutral" />
          <KpiCard
            label="平台积分总余额"
            value={Number(overview.totalBalanceCredits ?? 0).toFixed(2)}
            status="neutral"
          />
          <KpiCard
            label="本月全站消耗"
            value={Number(overview.totalMonthlySpentCredits ?? 0).toFixed(4)}
            status="warning"
          />
          <KpiCard
            label="KV Cache 累计节省"
            value={Number(overview.totalCacheSavedCredits ?? 0).toFixed(4)}
            status="success"
          />
        </section>
      )}

      {/* VIP Plans + Manual Grant */}
      <section className="grid gap-6 lg:grid-cols-3">
        <Card className="p-4 lg:col-span-2">
          <h2 className="mb-3 text-sm font-semibold">VIP 四档权益配置（热更新即时生效）</h2>
          <div className="grid gap-3 sm:grid-cols-2">
            {plans.map((p) => (
              <Card key={p.planCode} className="p-3 text-xs">
                <div className="flex items-center justify-between">
                  <span className="font-semibold">
                    {p.displayName} ({p.planCode})
                  </span>
                  <Button
                    type="button"
                    onClick={() => openPlanEditor(p)}
                    variant="outline"
                    size="sm"
                  >
                    编辑配额
                  </Button>
                </div>
                <div className="mt-2 space-y-1 font-mono text-[11px] text-muted-foreground">
                  <div>
                    月价: {p.monthlyPriceCredits} Cr · 月赠: {p.monthlyGrantCredits} Cr · 折扣:{" "}
                    {p.creditDiscountBps} bps
                  </div>
                  <div>
                    记忆: {p.entitlements.maxMemoryChapters} 章 · 资料库:{" "}
                    {p.entitlements.userLibraryMaxDocs} 份
                  </div>
                  <div>
                    Ripple 范围: {p.entitlements.rippleMaxRangeChapters} 章 · 速率:{" "}
                    {p.entitlements.rpmLimit} RPM
                  </div>
                </div>
              </Card>
            ))}
          </div>

          {editingPlan && (
            <Card className="mt-4 p-4">
              <form onSubmit={handleSavePlan} className="grid gap-3 sm:grid-cols-4">
                <div className="sm:col-span-4 text-xs font-semibold">
                  编辑套餐：{editingPlan.displayName} ({editingPlan.planCode})
                </div>
                <div className="space-y-1">
                  <label htmlFor="editPriceCredits" className="text-[11px] text-muted-foreground">
                    月费积分
                  </label>
                  <Input
                    id="editPriceCredits"
                    type="number"
                    step="0.01"
                    value={editPriceCredits}
                    onChange={(e) => setEditPriceCredits(e.target.value)}
                    className="font-mono"
                  />
                </div>
                <div className="space-y-1">
                  <label htmlFor="editGrantCredits" className="text-[11px] text-muted-foreground">
                    月赠积分
                  </label>
                  <Input
                    id="editGrantCredits"
                    type="number"
                    step="0.01"
                    value={editGrantCredits}
                    onChange={(e) => setEditGrantCredits(e.target.value)}
                    className="font-mono"
                  />
                </div>
                <div className="space-y-1">
                  <label htmlFor="editDiscountBps" className="text-[11px] text-muted-foreground">
                    折扣 (bps)
                  </label>
                  <Input
                    id="editDiscountBps"
                    type="number"
                    value={editDiscountBps}
                    onChange={(e) => setEditDiscountBps(e.target.value)}
                    className="font-mono"
                  />
                </div>
                <div className="space-y-1">
                  <label htmlFor="editRippleRange" className="text-[11px] text-muted-foreground">
                    Ripple 最大章数
                  </label>
                  <Input
                    id="editRippleRange"
                    type="number"
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
                  <Button type="submit" disabled={busy} variant="default" size="sm">
                    保存热更新
                  </Button>
                </div>
              </form>
            </Card>
          )}
        </Card>

        <Card className="p-4">
          <form onSubmit={handleGrantVip} className="space-y-3">
            <h2 className="text-sm font-semibold">人工授信 VIP 会员</h2>
            <div className="space-y-1">
              <label htmlFor="grantUserId" className="text-xs text-muted-foreground">
                目标用户 UUID
              </label>
              <Input
                id="grantUserId"
                required
                value={grantUserId}
                onChange={(e) => setGrantUserId(e.target.value)}
                placeholder="UUID"
                className="font-mono"
              />
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div className="space-y-1">
                <Select
                  id="grantPlanCode"
                  label="档位"
                  value={grantPlanCode}
                  onValueChange={(value) => value !== null && setGrantPlanCode(value)}
                  options={[
                    { value: "VIP", label: "VIP（签约会员）" },
                    { value: "SVIP", label: "SVIP（白金会员）" },
                    { value: "STUDIO", label: "STUDIO（工作室版）" },
                    { value: "FREE", label: "FREE（免费版）" },
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
            <label className="flex items-center gap-2 text-xs text-muted-foreground">
              <Input
                type="checkbox"
                checked={grantBonus}
                onChange={(e) => setGrantBonus(e.target.checked)}
              />
              <span>同步发放该档位当月赠送积分</span>
            </label>
            <Button type="submit" loading={busy} variant="default" size="sm" className="w-full">
              确认发放 VIP
            </Button>
          </form>
        </Card>
      </section>

      {/* Model Breakdown + Recent Ledger */}
      {overview && (
        <Card className="p-4">
          <h2 className="mb-3 text-sm font-semibold">分模型消耗与 KV Cache 命中统计</h2>
          <DataTable
            caption="分模型消耗与 KV Cache 命中统计"
            rows={overview.modelBreakdown}
            rowKey={(m) => m.modelAlias}
            empty={<EmptyState title="暂无模型消耗记录" />}
            columns={[
              {
                id: "model",
                header: "模型别名",
                className: "font-semibold text-primary",
                cell: (m) => m.modelAlias,
              },
              { id: "runs", header: "结算次数", cell: (m) => m.runsCount },
              {
                id: "input",
                header: "Input / Cached Tokens",
                cell: (m) => (
                  <>
                    {m.totalInputTokens.toLocaleString()} / {m.totalCachedTokens.toLocaleString()}
                  </>
                ),
              },
              {
                id: "hitRate",
                header: "缓存命中率",
                className: "text-status-success",
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
                header: "Output Tokens",
                cell: (m) => m.totalOutputTokens.toLocaleString(),
              },
              {
                id: "spent",
                header: "实际消耗 (Credits)",
                align: "end",
                cell: (m) => Number(m.totalSpentCredits).toFixed(4),
              },
            ]}
          />
        </Card>
      )}

      {overview && (
        <Card className="p-4">
          <h2 className="mb-3 text-sm font-semibold">
            最近全站记账流水 ({overview.recentLedger.length})
          </h2>
          <DataTable
            caption="最近全站记账流水"
            rows={overview.recentLedger.slice(0, 25)}
            rowKey={(row) => row.id}
            empty={<EmptyState title="暂无记账流水" />}
            columns={[
              { id: "type", header: "类型", cell: (row) => row.entryType },
              { id: "model", header: "模型", cell: (row) => row.modelAlias ?? "-" },
              {
                id: "description",
                header: "说明",
                className: "font-sans text-muted-foreground",
                cell: (row) => row.description,
              },
              {
                id: "amount",
                header: "变动",
                align: "end",
                cell: (row) => Number(row.amountCredits).toFixed(4),
              },
              {
                id: "balance",
                header: "结余",
                align: "end",
                cell: (row) => Number(row.balanceAfterCredits).toFixed(4),
              },
            ]}
          />
        </Card>
      )}
    </AdminShell>
  );
}
