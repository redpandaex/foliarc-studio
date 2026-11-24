"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useAuth } from "@/components/auth-provider";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  ApiClientError,
  billingApi,
  type BillingDashboardData,
} from "@/lib/api";

const ENTRY_STYLE: Record<string, { label: string; cls: string }> = {
  GRANT: { label: "初始赠送", cls: "bg-emerald-500/15 text-emerald-600" },
  TOPUP: { label: "账户充值", cls: "bg-emerald-500/15 text-emerald-600" },
  FREEZE: { label: "预冻结", cls: "bg-amber-500/15 text-amber-600" },
  SETTLE: { label: "精准结算", cls: "bg-sky-500/15 text-sky-600" },
  RELEASE: { label: "解冻退还", cls: "bg-violet-500/15 text-violet-600" },
};

export default function BillingDashboardPage() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();
  const [data, setData] = useState<BillingDashboardData | null>(null);
  const [budgetInput, setBudgetInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadDashboard = useCallback(async () => {
    try {
      const snap = await billingApi.dashboard();
      setData(snap);
      setBudgetInput(String(snap.account.monthlyBudgetCredits));
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "加载计费账本失败");
    }
  }, []);

  useEffect(() => {
    if (!authLoading && !user) {
      router.replace("/login");
    }
  }, [authLoading, user, router]);

  useEffect(() => {
    if (!user) return;
    let active = true;
    billingApi
      .dashboard()
      .then((snap) => {
        if (!active) return;
        setData(snap);
        setBudgetInput(String(snap.account.monthlyBudgetCredits));
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(err instanceof ApiClientError ? err.message : "加载计费账本失败");
      });
    return () => {
      active = false;
    };
  }, [user]);

  async function handleTopUp(amount: number) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await billingApi.topUp(amount);
      setNotice(`已成功幂等充值 +${amount} Credits`);
      await loadDashboard();
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "充值失败");
    } finally {
      setBusy(false);
    }
  }

  async function handleSaveBudget(e: FormEvent) {
    e.preventDefault();
    const num = Number.parseFloat(budgetInput);
    if (Number.isNaN(num) || num < 1) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await billingApi.updateBudget(num);
      setNotice(`月度硬熔断预算已更新为 ${num} Credits`);
      await loadDashboard();
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "更新预算失败");
    } finally {
      setBusy(false);
    }
  }

  if (authLoading || !user) {
    return (
      <div className="flex min-h-screen items-center justify-center text-sm text-muted-foreground">
        校验登录态中...
      </div>
    );
  }

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-6 py-8">
      <header className="flex flex-wrap items-center justify-between gap-4 border-b border-border pb-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">计费账本与网关监控中心</h1>
          <p className="text-xs text-muted-foreground">
            两阶段幂等扣费（Freeze + Settle）· Prompt Cache 成本节省 · Redis+Lua RPM/TPM 限流 · Resilience4j 熔断舱壁
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Link href="/books" className={buttonVariants({ variant: "outline", size: "sm" })}>
            ← 返回书架
          </Link>
          <Button size="sm" variant="outline" onClick={() => void loadDashboard()}>
            刷新数据
          </Button>
        </div>
      </header>

      {notice && (
        <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/10 px-4 py-2 text-xs text-emerald-700 dark:text-emerald-400">
          {notice}
        </div>
      )}
      {error && (
        <div className="rounded-lg border border-rose-500/30 bg-rose-500/10 px-4 py-2 text-xs text-rose-600">
          {error}
        </div>
      )}

      {data && (
        <>
          {/* KPI Cards */}
          <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <div className="rounded-xl border border-border bg-card p-4">
              <div className="text-xs text-muted-foreground">可用余额 (Balance)</div>
              <div className="mt-1 font-mono text-2xl font-bold">
                {data.account.balanceCredits.toFixed(4)}
              </div>
              <div className="mt-1 text-[11px] text-muted-foreground">
                冻结中：{data.account.frozenCredits.toFixed(4)} Credits
              </div>
              <div className="mt-3 flex gap-2">
                <Button size="xs" disabled={busy} onClick={() => void handleTopUp(20)}>
                  +20 充值
                </Button>
                <Button size="xs" variant="outline" disabled={busy} onClick={() => void handleTopUp(100)}>
                  +100 充值
                </Button>
              </div>
            </div>

            <div className="rounded-xl border border-border bg-card p-4">
              <div className="text-xs text-muted-foreground">
                本月已消耗 ({data.account.budgetMonth})
              </div>
              <div className="mt-1 font-mono text-2xl font-bold">
                {data.account.monthlySpentCredits.toFixed(4)}
              </div>
              <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-muted">
                <div
                  className="h-full bg-primary"
                  style={{ width: `${Math.min(100, data.account.budgetUsageRatio)}%` }}
                />
              </div>
              <form onSubmit={handleSaveBudget} className="mt-2.5 flex items-center gap-1.5">
                <span className="text-[11px] text-muted-foreground">上限</span>
                <input
                  type="number"
                  min={1}
                  step="1"
                  value={budgetInput}
                  onChange={(e) => setBudgetInput(e.target.value)}
                  className="w-20 rounded border border-border bg-background px-1.5 py-0.5 font-mono text-xs"
                />
                <Button type="submit" size="xs" variant="outline" disabled={busy}>
                  保存
                </Button>
              </form>
            </div>

            <div className="rounded-xl border border-border bg-card p-4">
              <div className="text-xs text-muted-foreground">KV Cache 累计省费</div>
              <div className="mt-1 font-mono text-2xl font-bold text-emerald-600">
                {data.totalCacheSavedCredits.toFixed(4)}
              </div>
              <div className="mt-1 text-[11px] text-muted-foreground">
                Prompt Cache 命中率：{data.cacheHitRatio.toFixed(1)}%
              </div>
              <div className="mt-2 text-[10px] text-muted-foreground">
                静态前缀缓存享受约 90% 输入 Token 折扣
              </div>
            </div>

            <div className="rounded-xl border border-border bg-card p-4">
              <div className="text-xs text-muted-foreground">AI 网关韧性状态</div>
              <div className="mt-1 flex items-center gap-2">
                <span className="inline-block h-2.5 w-2.5 rounded-full bg-emerald-500" />
                <span className="font-mono text-lg font-bold">
                  {data.gateway.circuitBreakerState}
                </span>
              </div>
              <div className="mt-1.5 space-y-0.5 font-mono text-[11px] text-muted-foreground">
                <div>
                  舱壁并发：{data.gateway.bulkheadAvailableCalls}/{data.gateway.bulkheadMaxConcurrentCalls}
                </div>
                <div>
                  限流：{data.gateway.rpmLimit} RPM · {data.gateway.tpmLimit.toLocaleString()} TPM
                </div>
              </div>
            </div>
          </section>

          {/* Model Cost Breakdown */}
          {data.modelBreakdown.length > 0 && (
            <section className="rounded-xl border border-border bg-card p-4">
              <h2 className="mb-3 text-sm font-semibold">分模型消耗与缓存命中统计</h2>
              <div className="overflow-x-auto">
                <table className="w-full text-left font-mono text-xs">
                  <thead className="border-b border-border text-[11px] text-muted-foreground">
                    <tr>
                      <th className="py-2">模型别名</th>
                      <th className="py-2">结算次数</th>
                      <th className="py-2">Input / Cached</th>
                      <th className="py-2">Output</th>
                      <th className="py-2 text-right">实际花费 (Credits)</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {data.modelBreakdown.map((m) => (
                      <tr key={m.modelAlias}>
                        <td className="py-2 font-semibold">{m.modelAlias}</td>
                        <td className="py-2">{m.runsCount}</td>
                        <td className="py-2">
                          {m.totalInputTokens.toLocaleString()} / {m.totalCachedTokens.toLocaleString()}
                        </td>
                        <td className="py-2">{m.totalOutputTokens.toLocaleString()}</td>
                        <td className="py-2 text-right">{m.totalSpentCredits.toFixed(4)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}

          {/* Double-Entry Audit Ledger */}
          <section className="rounded-xl border border-border bg-card p-4">
            <h2 className="mb-3 text-sm font-semibold">复式幂等记账流水（最近 50 笔）</h2>
            {data.recentLedger.length === 0 ? (
              <p className="text-xs text-muted-foreground">暂无账单流水。</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs">
                  <thead className="border-b border-border text-[11px] text-muted-foreground">
                    <tr>
                      <th className="py-2">类型</th>
                      <th className="py-2">幂等键 (request_id)</th>
                      <th className="py-2">模型 / Tokens</th>
                      <th className="py-2">说明</th>
                      <th className="py-2 text-right">变动额度</th>
                      <th className="py-2 text-right">结余</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {data.recentLedger.map((row) => {
                      const badge = ENTRY_STYLE[row.entryType] ?? {
                        label: row.entryType,
                        cls: "bg-muted text-muted-foreground",
                      };
                      return (
                        <tr key={row.id}>
                          <td className="py-2">
                            <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${badge.cls}`}>
                              {badge.label}
                            </span>
                          </td>
                          <td className="py-2 font-mono text-[10px] text-muted-foreground">
                            {row.requestId.slice(0, 28)}
                          </td>
                          <td className="py-2 font-mono text-[11px]">
                            {row.modelAlias ? (
                              <span>
                                {row.modelAlias} ({row.inputTokens}/{row.cachedTokens}/{row.outputTokens})
                              </span>
                            ) : (
                              "-"
                            )}
                          </td>
                          <td className="py-2 text-muted-foreground">{row.description}</td>
                          <td className="py-2 text-right font-mono">
                            {row.amountCredits > 0 && row.entryType !== "SETTLE"
                              ? `+${row.amountCredits.toFixed(4)}`
                              : row.amountCredits.toFixed(4)}
                          </td>
                          <td className="py-2 text-right font-mono font-medium">
                            {row.balanceAfterCredits.toFixed(4)}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </main>
  );
}
