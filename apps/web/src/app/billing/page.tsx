"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useAuth } from "@/components/auth-provider";
import { Alert } from "@/components/ui/alert";
import { Badge, type UiStatus } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { KpiCard } from "@/components/ui/kpi-card";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { useToast } from "@/components/ui/toast";
import { billingApi, type BillingDashboardData } from "@/lib/api";
import { formatWebError } from "@/lib/error-messages";

const ENTRY_STYLE: Record<string, { label: string; status: UiStatus }> = {
  GRANT: { label: "赠送", status: "success" },
  TOPUP: { label: "充值", status: "success" },
  FREEZE: { label: "冻结", status: "warning" },
  SETTLE: { label: "扣费", status: "info" },
  RELEASE: { label: "退还", status: "ai" },
};

export default function BillingDashboardPage() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();
  const toast = useToast();
  const [data, setData] = useState<BillingDashboardData | null>(null);
  const [budgetInput, setBudgetInput] = useState("");
  const [showModels, setShowModels] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadDashboard = useCallback(async () => {
    try {
      const snap = await billingApi.dashboard();
      setData(snap);
      setBudgetInput(String(snap.account.monthlyBudgetCredits));
      setError(null);
    } catch (err) {
      setError(formatWebError(err, "加载账单失败，请稍后重试", { action: "billing" }));
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
        setError(formatWebError(err, "加载账单失败，请稍后重试", { action: "billing" }));
      });
    return () => {
      active = false;
    };
  }, [user]);

  async function handleTopUp(amount: number) {
    setBusy(true);
    try {
      await billingApi.topUp(amount);
      toast.success(`已充值 ${amount} 积分`);
      await loadDashboard();
    } catch (err) {
      toast.error(formatWebError(err, "充值失败，请稍后重试", { action: "billing" }));
    } finally {
      setBusy(false);
    }
  }

  async function handleSaveBudget(e: FormEvent) {
    e.preventDefault();
    const num = Number.parseFloat(budgetInput);
    if (Number.isNaN(num) || num < 1) return;
    setBusy(true);
    try {
      await billingApi.updateBudget(num);
      toast.success(`已将本月预算调整为 ${num} 积分`);
      await loadDashboard();
    } catch (err) {
      toast.error(formatWebError(err, "更新预算失败，请稍后重试", { action: "billing" }));
    } finally {
      setBusy(false);
    }
  }

  if (authLoading || !user) {
    return (
      <div className="flex min-h-screen items-center justify-center text-xs text-muted-foreground">
        加载中…
      </div>
    );
  }

  type ModelRow = BillingDashboardData["modelBreakdown"][number];
  type LedgerRow = BillingDashboardData["recentLedger"][number];

  const modelColumns: DataTableColumn<ModelRow>[] = [
    {
      id: "model",
      header: "模型",
      cell: (m) => <span className="font-mono font-semibold">{m.modelAlias}</span>,
    },
    {
      id: "runs",
      header: "次数",
      cell: (m) => <span className="font-mono tabular-nums">{m.runsCount}</span>,
    },
    {
      id: "input",
      header: "输入 / 缓存 Token",
      cell: (m) => (
        <span className="font-mono tabular-nums">
          {m.totalInputTokens.toLocaleString()} / {m.totalCachedTokens.toLocaleString()}
        </span>
      ),
    },
    {
      id: "output",
      header: "输出 Token",
      cell: (m) => (
        <span className="font-mono tabular-nums">{m.totalOutputTokens.toLocaleString()}</span>
      ),
    },
    {
      id: "spent",
      header: "消耗（积分）",
      align: "end",
      cell: (m) => <span className="font-mono tabular-nums">{m.totalSpentCredits.toFixed(4)}</span>,
    },
  ];

  const ledgerColumns: DataTableColumn<LedgerRow>[] = [
    {
      id: "type",
      header: "类型",
      cell: (row) => {
        const meta = ENTRY_STYLE[row.entryType] ?? {
          label: row.entryType,
          status: "neutral" as UiStatus,
        };
        return <Badge status={meta.status}>{meta.label}</Badge>;
      },
    },
    {
      id: "model",
      header: "模型",
      cell: (row) => (row.modelAlias ? <span className="font-mono text-xs">{row.modelAlias}</span> : "-"),
    },
    {
      id: "desc",
      header: "摘要",
      cell: (row) => <span className="text-muted-foreground">{row.description || "-"}</span>,
    },
    {
      id: "amount",
      header: "变动（积分）",
      align: "end",
      cell: (row) => (
        <span className="font-mono tabular-nums">
          {row.amountCredits > 0 && row.entryType !== "SETTLE" && row.entryType !== "FREEZE"
            ? `+${row.amountCredits.toFixed(4)}`
            : row.amountCredits.toFixed(4)}
        </span>
      ),
    },
    {
      id: "balance",
      header: "余额（积分）",
      align: "end",
      cell: (row) => (
        <span className="font-mono font-medium tabular-nums">
          {row.balanceAfterCredits.toFixed(4)}
        </span>
      ),
    },
  ];

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="border-b border-border bg-card">
        <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <Link
              href="/books"
              className="text-xs font-medium text-muted-foreground hover:text-foreground"
            >
              书架
            </Link>
            <span className="text-muted-foreground">/</span>
            <h1 className="text-sm font-semibold">积分与账单</h1>
          </div>
          <div className="flex items-center gap-2">
            <ThemeToggle />
            <Link href="/vip" className={buttonVariants({ variant: "outline", size: "sm" })}>
              会员
            </Link>
            <Button size="sm" variant="outline" onClick={() => void loadDashboard()}>
              刷新
            </Button>
          </div>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-6 py-8">
        {error && (
          <Alert
            status="danger"
            announce="assertive"
            action={
              <Button type="button" variant="outline" size="sm" onClick={() => void loadDashboard()}>
                重新加载
              </Button>
            }
          >
            {error}
          </Alert>
        )}

        {data && (
          <>
            <section className="grid gap-4 sm:grid-cols-3">
              <KpiCard
                label="可用积分"
                value={data.account.balanceCredits.toFixed(2)}
                detail={
                  <div className="space-y-2">
                    <div className="tabular-nums">冻结中：{data.account.frozenCredits.toFixed(2)}</div>
                    <div className="flex gap-2">
                      <Button size="xs" disabled={busy} onClick={() => void handleTopUp(20)}>
                        充值 20
                      </Button>
                      <Button
                        size="xs"
                        variant="outline"
                        disabled={busy}
                        onClick={() => void handleTopUp(100)}
                      >
                        充值 100
                      </Button>
                    </div>
                  </div>
                }
              />

              <KpiCard
                label={`本月已用（${data.account.budgetMonth}）`}
                value={data.account.monthlySpentCredits.toFixed(2)}
                detail={
                  <div className="space-y-2">
                    <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
                      <div
                        className="h-full bg-primary"
                        style={{ width: `${Math.min(100, data.account.budgetUsageRatio)}%` }}
                      />
                    </div>
                    <form onSubmit={handleSaveBudget} className="flex items-center gap-1.5">
                      <label htmlFor="monthly-budget" className="text-muted-foreground">
                        预算
                      </label>
                      <Input
                        id="monthly-budget"
                        type="number"
                        min={1}
                        step="1"
                        value={budgetInput}
                        onChange={(e) => setBudgetInput(e.target.value)}
                        className="h-6 w-20 px-1.5 font-mono text-xs"
                      />
                      <Button type="submit" size="xs" variant="outline" disabled={busy}>
                        保存
                      </Button>
                    </form>
                  </div>
                }
              />

              <KpiCard
                label="缓存节省"
                value={data.totalCacheSavedCredits.toFixed(4)}
                status="success"
                detail={`命中率 ${data.cacheHitRatio.toFixed(1)}%`}
              />
            </section>

            {data.modelBreakdown.length > 0 && (
              <Card className="p-4">
                <div className="flex items-center justify-between gap-2">
                  <h2 className="text-sm font-semibold">
                    分模型用量 ({data.modelBreakdown.length})
                  </h2>
                  <Button
                    type="button"
                    variant="ghost"
                    size="xs"
                    aria-expanded={showModels}
                    onClick={() => setShowModels((v) => !v)}
                  >
                    {showModels ? "收起" : "展开"}
                  </Button>
                </div>
                {showModels && (
                  <div className="mt-3">
                    <DataTable
                      caption="分模型用量"
                      columns={modelColumns}
                      rows={data.modelBreakdown}
                      rowKey={(m) => m.modelAlias}
                      empty={<EmptyState title="暂无模型用量" />}
                    />
                  </div>
                )}
              </Card>
            )}

            <Card className="p-4">
              <h2 className="mb-3 text-sm font-semibold">积分流水</h2>
              <DataTable
                caption="积分流水"
                columns={ledgerColumns}
                rows={data.recentLedger}
                rowKey={(r) => r.id}
                empty={<EmptyState title="暂无流水记录" />}
              />
            </Card>
          </>
        )}
      </main>
    </div>
  );
}
