"use client";

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

  const [notice, setNotice] = useState<string | null>(null);
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
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载计费与 VIP 失败");
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
        setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载计费与 VIP 失败");
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
    setNotice(null);
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
      setNotice(`套餐 ${editingPlan.planCode} 已热更新生效并记录审计日志。`);
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
    setNotice(null);
    setError(null);
    try {
      await adminBillingApi.grantVip({
        userId: grantUserId.trim(),
        planCode: grantPlanCode,
        durationDays: Number.parseInt(grantDays, 10) || 30,
        grantBonusCredits: grantBonus,
        idempotencyKey: `admin-${grantPlanCode}-${Date.now()}`,
      });
      setNotice(`已为用户 ${grantUserId.slice(0, 10)} 授予 ${grantPlanCode} (${grantDays} 天)。`);
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
        <button
          type="button"
          onClick={() => void loadData()}
          className="rounded-md border border-border bg-card px-3 py-1.5 text-xs hover:bg-muted"
        >
          刷新数据
        </button>
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

      {overview && (
        <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <div className="rounded-xl border border-border bg-card p-4">
            <div className="text-xs text-muted-foreground">计费账户总数</div>
            <div className="mt-1 font-mono text-2xl font-bold">{overview.totalAccounts}</div>
          </div>
          <div className="rounded-xl border border-border bg-card p-4">
            <div className="text-xs text-muted-foreground">平台积分总余额</div>
            <div className="mt-1 font-mono text-2xl font-bold">
              {Number(overview.totalBalanceCredits ?? 0).toFixed(2)}
            </div>
          </div>
          <div className="rounded-xl border border-border bg-card p-4">
            <div className="text-xs text-muted-foreground">本月全站消耗</div>
            <div className="mt-1 font-mono text-2xl font-bold text-amber-300">
              {Number(overview.totalMonthlySpentCredits ?? 0).toFixed(4)}
            </div>
          </div>
          <div className="rounded-xl border border-border bg-card p-4">
            <div className="text-xs text-muted-foreground">KV Cache 累计节省</div>
            <div className="mt-1 font-mono text-2xl font-bold text-emerald-400">
              {Number(overview.totalCacheSavedCredits ?? 0).toFixed(4)}
            </div>
          </div>
        </section>
      )}

      {/* VIP Plans + Manual Grant */}
      <section className="grid gap-6 lg:grid-cols-3">
        <div className="rounded-xl border border-border bg-card p-4 lg:col-span-2">
          <h2 className="mb-3 text-sm font-semibold">VIP 四档权益配置（热更新即时生效）</h2>
          <div className="grid gap-3 sm:grid-cols-2">
            {plans.map((p) => (
              <div key={p.planCode} className="rounded-lg border border-border bg-background/50 p-3 text-xs">
                <div className="flex items-center justify-between">
                  <span className="font-semibold">
                    {p.displayName} ({p.planCode})
                  </span>
                  <button
                    type="button"
                    onClick={() => openPlanEditor(p)}
                    className="rounded border border-border px-2 py-0.5 text-[11px] hover:bg-muted"
                  >
                    编辑配额
                  </button>
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
              </div>
            ))}
          </div>

          {editingPlan && (
            <form
              onSubmit={handleSavePlan}
              className="mt-4 grid gap-3 rounded-lg border border-blue-500/40 bg-blue-500/5 p-4 sm:grid-cols-4"
            >
              <div className="sm:col-span-4 text-xs font-semibold">
                编辑套餐：{editingPlan.displayName} ({editingPlan.planCode})
              </div>
              <div className="space-y-1">
                <label className="text-[11px] text-muted-foreground">月费积分</label>
                <input
                  type="number"
                  step="0.01"
                  value={editPriceCredits}
                  onChange={(e) => setEditPriceCredits(e.target.value)}
                  className="w-full rounded border border-border bg-background px-2 py-1 font-mono text-xs"
                />
              </div>
              <div className="space-y-1">
                <label className="text-[11px] text-muted-foreground">月赠积分</label>
                <input
                  type="number"
                  step="0.01"
                  value={editGrantCredits}
                  onChange={(e) => setEditGrantCredits(e.target.value)}
                  className="w-full rounded border border-border bg-background px-2 py-1 font-mono text-xs"
                />
              </div>
              <div className="space-y-1">
                <label className="text-[11px] text-muted-foreground">折扣 (bps)</label>
                <input
                  type="number"
                  value={editDiscountBps}
                  onChange={(e) => setEditDiscountBps(e.target.value)}
                  className="w-full rounded border border-border bg-background px-2 py-1 font-mono text-xs"
                />
              </div>
              <div className="space-y-1">
                <label className="text-[11px] text-muted-foreground">Ripple 最大章数</label>
                <input
                  type="number"
                  value={editRippleRange}
                  onChange={(e) => setEditRippleRange(e.target.value)}
                  className="w-full rounded border border-border bg-background px-2 py-1 font-mono text-xs"
                />
              </div>
              <div className="flex justify-end gap-2 sm:col-span-4">
                <button
                  type="button"
                  onClick={() => setEditingPlan(null)}
                  className="rounded border border-border px-3 py-1 text-xs hover:bg-muted"
                >
                  取消
                </button>
                <button
                  type="submit"
                  disabled={busy}
                  className="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-500"
                >
                  保存热更新
                </button>
              </div>
            </form>
          )}
        </div>

        <form onSubmit={handleGrantVip} className="space-y-3 rounded-xl border border-border bg-card p-4">
          <h2 className="text-sm font-semibold">人工授信 VIP 会员</h2>
          <div className="space-y-1">
            <label className="text-xs text-muted-foreground">目标用户 UUID</label>
            <input
              required
              value={grantUserId}
              onChange={(e) => setGrantUserId(e.target.value)}
              placeholder="UUID"
              className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 font-mono text-xs"
            />
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div className="space-y-1">
              <label className="text-xs text-muted-foreground">档位</label>
              <select
                value={grantPlanCode}
                onChange={(e) => setGrantPlanCode(e.target.value)}
                className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-xs"
              >
                <option value="VIP">VIP（签约会员）</option>
                <option value="SVIP">SVIP（白金会员）</option>
                <option value="STUDIO">STUDIO（工作室版）</option>
                <option value="FREE">FREE（免费版）</option>
              </select>
            </div>
            <div className="space-y-1">
              <label className="text-xs text-muted-foreground">天数</label>
              <input
                type="number"
                min={1}
                value={grantDays}
                onChange={(e) => setGrantDays(e.target.value)}
                className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 font-mono text-xs"
              />
            </div>
          </div>
          <label className="flex items-center gap-2 text-xs text-muted-foreground">
            <input
              type="checkbox"
              checked={grantBonus}
              onChange={(e) => setGrantBonus(e.target.checked)}
            />
            <span>同步发放该档位当月赠送积分</span>
          </label>
          <button
            type="submit"
            disabled={busy}
            className="w-full rounded-md bg-blue-600 py-1.5 text-xs font-medium text-white hover:bg-blue-500"
          >
            确认发放 VIP
          </button>
        </form>
      </section>

      {/* Model Breakdown + Recent Ledger */}
      {overview && overview.modelBreakdown.length > 0 && (
        <section className="rounded-xl border border-border bg-card p-4">
          <h2 className="mb-3 text-sm font-semibold">分模型消耗与 KV Cache 命中统计</h2>
          <div className="overflow-x-auto">
            <table className="w-full text-left font-mono text-xs">
              <thead className="border-b border-border text-[11px] text-muted-foreground">
                <tr>
                  <th className="py-2">模型别名</th>
                  <th className="py-2">结算次数</th>
                  <th className="py-2">Input / Cached Tokens</th>
                  <th className="py-2">缓存命中率</th>
                  <th className="py-2">Output Tokens</th>
                  <th className="py-2 text-right">实际消耗 (Credits)</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {overview.modelBreakdown.map((m) => {
                  const hitPct =
                    m.totalInputTokens > 0
                      ? ((m.totalCachedTokens / m.totalInputTokens) * 100).toFixed(1)
                      : "0.0";
                  return (
                    <tr key={m.modelAlias}>
                      <td className="py-2 font-semibold text-blue-300">{m.modelAlias}</td>
                      <td className="py-2">{m.runsCount}</td>
                      <td className="py-2">
                        {m.totalInputTokens.toLocaleString()} / {m.totalCachedTokens.toLocaleString()}
                      </td>
                      <td className="py-2 text-emerald-400">{hitPct}%</td>
                      <td className="py-2">{m.totalOutputTokens.toLocaleString()}</td>
                      <td className="py-2 text-right">{Number(m.totalSpentCredits).toFixed(4)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {overview && (
        <section className="rounded-xl border border-border bg-card p-4">
          <h2 className="mb-3 text-sm font-semibold">最近全站记账流水 ({overview.recentLedger.length})</h2>
          <div className="overflow-x-auto">
            <table className="w-full text-left font-mono text-xs">
              <thead className="border-b border-border text-[11px] text-muted-foreground">
                <tr>
                  <th className="py-2">类型</th>
                  <th className="py-2">模型</th>
                  <th className="py-2">说明</th>
                  <th className="py-2 text-right">变动</th>
                  <th className="py-2 text-right">结余</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {overview.recentLedger.slice(0, 25).map((row) => (
                  <tr key={row.id}>
                    <td className="py-2">{row.entryType}</td>
                    <td className="py-2">{row.modelAlias ?? "-"}</td>
                    <td className="py-2 font-sans text-muted-foreground">{row.description}</td>
                    <td className="py-2 text-right">{Number(row.amountCredits).toFixed(4)}</td>
                    <td className="py-2 text-right">{Number(row.balanceAfterCredits).toFixed(4)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </AdminShell>
  );
}
