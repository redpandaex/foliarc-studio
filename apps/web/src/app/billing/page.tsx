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
import {
  ApiClientError,
  billingApi,
  type BillingDashboardData,
} from "@/lib/api";

const ENTRY_STYLE: Record<string, { label: string; status: UiStatus }> = {
  GRANT: { label: "初始赠送", status: "success" },
  TOPUP: { label: "账户充值", status: "success" },
  FREEZE: { label: "预冻结", status: "warning" },
  SETTLE: { label: "精准结算", status: "info" },
  RELEASE: { label: "解冻退还", status: "ai" },
};

export default function BillingDashboardPage() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();
  const toast = useToast();
  const [data, setData] = useState<BillingDashboardData | null>(null);
  const [budgetInput, setBudgetInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadDashboard = useCallback(async () => {
    try {
      const snap = await billingApi.dashboard();
      setData(snap);
      setBudgetInput(String(snap.account.monthlyBudgetCredits));
      setError(null);
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
    try {
      await billingApi.topUp(amount);
      toast.success(`已成功幂等充值 +${amount} Credits`);
      await loadDashboard();
    } catch (err) {
      toast.error(err instanceof ApiClientError ? err.message : "充值失败");
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
      toast.success(`月度硬熔断预算已更新为 ${num} Credits`);
      await loadDashboard();
    } catch (err) {
      toast.error(err instanceof ApiClientError ? err.message : "更新预算失败");
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

  type ModelRow = BillingDashboardData["modelBreakdown"][number];
  type LedgerRow = BillingDashboardData["recentLedger"][number];

  const modelColumns: DataTableColumn<ModelRow>[] = [
    {
      id: "model",
      header: "模型别名",
      cell: (m) => <span className="font-mono font-semibold">{m.modelAlias}</span>,
    },
    { id: "runs", header: "结算次数", cell: (m) => <span className="font-mono">{m.runsCount}</span> },
    {
      id: "input",
      header: "Input / Cached",
      cell: (m) => (
        <span className="font-mono">
          {m.totalInputTokens.toLocaleString()} / {m.totalCachedTokens.toLocaleString()}
        </span>
      ),
    },
    {
      id: "output",
      header: "Output",
      cell: (m) => <span className="font-mono">{m.totalOutputTokens.toLocaleString()}</span>,
    },
    {
      id: "spent",
      header: "实际花费 (Credits)",
      align: "end",
      cell: (m) => <span className="font-mono">{m.totalSpentCredits.toFixed(4)}</span>,
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
      id: "req",
      header: "幂等键 (request_id)",
      cell: (row) => (
        <span className="font-mono text-[10px] text-muted-foreground">
          {row.requestId.slice(0, 28)}
        </span>
      ),
    },
    {
      id: "model",
      header: "模型 / Tokens",
      cell: (row) =>
        row.modelAlias ? (
          <span className="font-mono text-[11px]">
            {row.modelAlias} ({row.inputTokens}/{row.cachedTokens}/{row.outputTokens})
          </span>
        ) : (
          "-"
        ),
    },
    {
      id: "desc",
      header: "说明",
      cell: (row) => <span className="text-muted-foreground">{row.description}</span>,
    },
    {
      id: "amount",
      header: "变动额度",
      align: "end",
      cell: (row) => (
        <span className="font-mono">
          {row.amountCredits > 0 && row.entryType !== "SETTLE"
            ? `+${row.amountCredits.toFixed(4)}`
            : row.amountCredits.toFixed(4)}
        </span>
      ),
    },
    {
      id: "balance",
      header: "结余",
      align: "end",
      cell: (row) => (
        <span className="font-mono font-medium">
          {row.balanceAfterCredits.toFixed(4)}
        </span>
      ),
    },
  ];

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-6 py-8">
      <header className="flex flex-wrap items-center justify-between gap-4 border-b border-border pb-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">个人计费账本</h1>
          <p className="text-xs text-muted-foreground">
            两阶段幂等扣费（Freeze + Settle）· Prompt Cache 成本节省 · 月度预算硬熔断
          </p>
        </div>
        <div className="flex items-center gap-2">
          <ThemeToggle />
          <Link href="/books" className={buttonVariants({ variant: "outline", size: "sm" })}>
            ← 返回书架
          </Link>
          <Button size="sm" variant="outline" onClick={() => void loadDashboard()}>
            刷新数据
          </Button>
        </div>
      </header>

      {error && (
        <Alert status="danger" announce="assertive">
          {error}
        </Alert>
      )}

      {data && (
        <>
          <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <KpiCard
              label="可用余额 (Balance)"
              value={data.account.balanceCredits.toFixed(4)}
              detail={
                <div className="space-y-2">
                  <div>冻结中：{data.account.frozenCredits.toFixed(4)} Credits</div>
                  <div className="flex gap-2">
                    <Button size="xs" disabled={busy} onClick={() => void handleTopUp(20)}>
                      +20 充值
                    </Button>
                    <Button size="xs" variant="outline" disabled={busy} onClick={() => void handleTopUp(100)}>
                      +100 充值
                    </Button>
                  </div>
                </div>
              }
            />

            <KpiCard
              label={`本月已消耗 (${data.account.budgetMonth})`}
              value={data.account.monthlySpentCredits.toFixed(4)}
              detail={
                <div className="space-y-2">
                  <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                    <div
                      className="h-full bg-primary"
                      style={{ width: `${Math.min(100, data.account.budgetUsageRatio)}%` }}
                    />
                  </div>
                  <form onSubmit={handleSaveBudget} className="flex items-center gap-1.5">
                    <span>上限</span>
                    <Input
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
              label="KV Cache 累计省费"
              value={data.totalCacheSavedCredits.toFixed(4)}
              status="success"
              detail={
                <div className="space-y-0.5">
                  <div>Prompt Cache 命中率：{data.cacheHitRatio.toFixed(1)}%</div>
                  <div>静态前缀缓存享受约 90% 输入折扣</div>
                </div>
              }
            />

            <KpiCard
              label="AI 网关韧性状态"
              value={data.gateway.circuitBreakerState}
              status={data.gateway.circuitBreakerState === "CLOSED" ? "success" : "warning"}
              detail={
                <div className="space-y-0.5 font-mono">
                  <div>
                    舱壁并发：{data.gateway.bulkheadAvailableCalls}/{data.gateway.bulkheadMaxConcurrentCalls}
                  </div>
                  <div>
                    限流：{data.gateway.rpmLimit} RPM · {data.gateway.tpmLimit.toLocaleString()} TPM
                  </div>
                </div>
              }
            />
          </section>

          {data.modelBreakdown.length > 0 && (
            <Card className="p-4">
              <h2 className="mb-3 text-sm font-semibold">分模型消耗与缓存命中统计</h2>
              <DataTable
                caption="分模型消耗与缓存命中统计"
                columns={modelColumns}
                rows={data.modelBreakdown}
                rowKey={(m) => m.modelAlias}
                empty={<EmptyState title="暂无模型消耗统计" />}
              />
            </Card>
          )}

          <Card className="p-4">
            <h2 className="mb-3 text-sm font-semibold">复式幂等记账流水（最近 50 笔）</h2>
            <DataTable
              caption="复式幂等记账流水"
              columns={ledgerColumns}
              rows={data.recentLedger}
              rowKey={(r) => r.id}
              empty={<EmptyState title="暂无账单流水" description="开始创作后将自动记录每笔积分扣费与退还流水。" />}
            />
          </Card>
        </>
      )}
    </main>
  );
}
