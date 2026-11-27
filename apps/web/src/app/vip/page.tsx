"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { SpotlightCard } from "@/components/motion/spotlight-card";
import { Card } from "@/components/ui/card";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { useToast } from "@/components/ui/toast";
import { vipApi, type MyVipStatus, type VipPlanItem } from "@/lib/api";
import { formatWebError } from "@/lib/error-messages";

function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024 * 1024) {
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  }
  return `${Math.round(bytes / (1024 * 1024))} MB`;
}

function formatPrice(cents: number): string {
  if (cents <= 0) return "免费";
  return `¥${(cents / 100).toFixed(0)} / 月`;
}

function formatDiscount(bps: number): string {
  if (bps >= 10000) return "原价";
  return `${(bps / 1000).toFixed(1)} 折`;
}

function makeOrderIdempotencyKey(planCode: string): string {
  const suffix =
    typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : `${Math.random().toString(36).slice(2)}`;
  return `web-${planCode}-${suffix}`;
}

export default function VipCenterPage() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();
  const toast = useToast();
  const [plans, setPlans] = useState<VipPlanItem[]>([]);
  const [status, setStatus] = useState<MyVipStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [submittingCode, setSubmittingCode] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadVip = useCallback(async () => {
    try {
      const [planList, myVip] = await Promise.all([vipApi.listPlans(), vipApi.me()]);
      setPlans(planList);
      setStatus(myVip);
      setError(null);
    } catch (err) {
      setError(formatWebError(err, "加载会员方案失败，请稍后重试"));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (authLoading) return;
    if (!user) {
      router.replace("/login");
      return;
    }
    let active = true;
    void Promise.all([vipApi.listPlans(), vipApi.me()])
      .then(([planList, myVip]) => {
        if (!active) return;
        setPlans(planList);
        setStatus(myVip);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(formatWebError(err, "加载会员方案失败，请稍后重试"));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [authLoading, user, router]);

  async function handleSubscribe(plan: VipPlanItem) {
    if (plan.planCode === "FREE") return;
    setSubmittingCode(plan.planCode);
    setError(null);
    try {
      const idemKey = makeOrderIdempotencyKey(plan.planCode);
      const res = await vipApi.subscribe({
        planCode: plan.planCode,
        months: 1,
        paymentChannel: "CREDITS",
        idempotencyKey: idemKey,
      });
      toast.success(
        `已开通「${res.displayName}」（${res.durationDays} 天），赠送 ${res.grantedBonusCredits.toFixed(0)} 积分`,
      );
      const latest = await vipApi.me();
      setStatus(latest);
    } catch (err) {
      toast.error(formatWebError(err, "开通会员失败，请稍后重试", { action: "billing" }));
    } finally {
      setSubmittingCode(null);
    }
  }

  if (authLoading || (!user && loading)) {
    return (
      <main className="mx-auto flex max-w-5xl flex-1 items-center justify-center px-6 py-16 text-xs text-muted-foreground">
        加载中…
      </main>
    );
  }

  const featureRows: { label: string; render: (p: VipPlanItem) => string }[] = [
    { label: "月赠积分", render: (p) => `${p.monthlyGrantCredits.toFixed(0)} 积分` },
    { label: "创作折扣", render: (p) => formatDiscount(p.creditDiscountBps) },
    {
      label: "长篇记忆",
      render: (p) => `${p.entitlements.maxMemoryChapters.toLocaleString()} 章`,
    },
    {
      label: "单节字数上限",
      render: (p) => `${(p.entitlements.maxSectionChars / 10000).toFixed(0)} 万字`,
    },
    {
      label: "资料库容量",
      render: (p) =>
        `${p.entitlements.userLibraryMaxDocs} 份 / ${formatBytes(p.entitlements.userLibraryMaxTotalBytes)}`,
    },
    {
      label: "跨章修订范围",
      render: (p) =>
        p.entitlements.rippleAllowFullBook
          ? "全书"
          : `≤ ${p.entitlements.rippleMaxRangeChapters} 章`,
    },
    {
      label: "单次修订上限",
      render: (p) => `${p.entitlements.rippleMaxPatchChapters} 章`,
    },
    { label: "创作速率", render: (p) => `${p.entitlements.rpmLimit} 次/分` },
  ];

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="border-b border-border bg-card">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <Link
              href="/books"
              className="text-xs font-medium text-muted-foreground hover:text-foreground"
            >
              书架
            </Link>
            <span className="text-muted-foreground">/</span>
            <h1 className="text-sm font-semibold">会员权益</h1>
          </div>
          <div className="flex items-center gap-2">
            <ThemeToggle />
            <Link href="/library" className={buttonVariants({ variant: "outline", size: "sm" })}>
              资料库
            </Link>
            <Link href="/billing" className={buttonVariants({ variant: "outline", size: "sm" })}>
              积分明细
            </Link>
          </div>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-6 py-8">
        {status && (
          <Card className="grid gap-4 p-5 sm:grid-cols-4">
            <div className="space-y-1">
              <div className="text-xs text-muted-foreground">当前方案</div>
              <div className="flex items-center gap-2">
                <span className="text-lg font-bold">{status.displayName}</span>
                <Badge status="vip">{formatDiscount(status.creditDiscountBps)}</Badge>
              </div>
              <div className="text-[11px] text-muted-foreground">
                {status.expiresAt
                  ? `有效期至 ${new Date(status.expiresAt).toLocaleDateString("zh-CN")}`
                  : "长期有效"}
              </div>
            </div>

            <div className="space-y-1">
              <div className="text-xs text-muted-foreground">资料库用量</div>
              <div className="text-base font-semibold tabular-nums">
                {status.libraryUsage.usedDocs} / {status.libraryUsage.maxDocs} 份
              </div>
              <div className="text-[11px] text-muted-foreground tabular-nums">
                {formatBytes(status.libraryUsage.usedBytes)} /{" "}
                {formatBytes(status.libraryUsage.maxTotalBytes)}
              </div>
            </div>

            <div className="space-y-1">
              <div className="text-xs text-muted-foreground">记忆与修订</div>
              <div className="text-base font-semibold tabular-nums">
                {status.entitlements.maxMemoryChapters.toLocaleString()} 章记忆
              </div>
              <div className="text-[11px] text-muted-foreground tabular-nums">
                跨章修订 ≤ {status.entitlements.rippleMaxRangeChapters.toLocaleString()} 章
              </div>
            </div>

            <div className="space-y-1">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>可用积分</span>
                <Link href="/billing" className="text-primary hover:underline">
                  积分明细 →
                </Link>
              </div>
              <div className="font-mono text-base font-semibold tabular-nums">
                {status.balanceCredits.toFixed(1)}
              </div>
              <div className="text-[11px] text-muted-foreground">
                月费与创作按折扣扣减
              </div>
            </div>
          </Card>
        )}

        {error && (
          <Alert
            status="danger"
            announce="assertive"
            action={
              <Button type="button" variant="outline" size="sm" onClick={() => void loadVip()}>
                重新加载
              </Button>
            }
          >
            {error}
          </Alert>
        )}

        <section className="space-y-4">
          <h2 className="text-base font-semibold">方案对比</h2>

          {loading ? (
            <p className="py-10 text-center text-xs text-muted-foreground">加载中…</p>
          ) : (
            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
              {plans.map((plan) => {
                const isCurrent = status?.planCode === plan.planCode;
                return (
                  <SpotlightCard
                    key={plan.planCode}
                    surface={isCurrent ? "elevated" : "card"}
                    className={`flex flex-col justify-between p-5 ${
                      isCurrent ? "border-status-vip-border" : ""
                    }`}
                  >
                    <div className="space-y-4">
                      <div>
                        <div className="flex items-center justify-between gap-2">
                          <h3 className="text-base font-bold">{plan.displayName}</h3>
                          {isCurrent && <Badge status="vip">当前方案</Badge>}
                        </div>
                        <div className="mt-2 flex items-baseline justify-between gap-2">
                          <span className="text-2xl font-bold tracking-tight tabular-nums">
                            {formatPrice(plan.monthlyPriceCents)}
                          </span>
                          {plan.monthlyPriceCredits > 0 && (
                            <span className="font-mono text-xs text-muted-foreground tabular-nums">
                              {plan.monthlyPriceCredits.toFixed(0)} 积分/月
                            </span>
                          )}
                        </div>
                      </div>

                      <dl className="divide-y divide-border/60 border-t border-border text-xs">
                        {featureRows.map((row) => (
                          <div
                            key={row.label}
                            className="flex items-center justify-between gap-2 py-2"
                          >
                            <dt className="text-muted-foreground">{row.label}</dt>
                            <dd className="font-mono font-medium text-foreground tabular-nums">
                              {row.render(plan)}
                            </dd>
                          </div>
                        ))}
                      </dl>
                    </div>

                    <div className="mt-5">
                      {plan.planCode === "FREE" ? (
                        <Button variant="outline" size="sm" className="w-full" disabled>
                          {isCurrent ? "当前方案" : "免费版"}
                        </Button>
                      ) : (
                        <Button
                          size="sm"
                          variant={isCurrent ? "outline" : "default"}
                          className="w-full"
                          disabled={submittingCode !== null}
                          loading={submittingCode === plan.planCode}
                          onClick={() => void handleSubscribe(plan)}
                        >
                          {submittingCode === plan.planCode
                            ? "开通中…"
                            : isCurrent
                              ? `用 ${plan.monthlyPriceCredits.toFixed(0)} 积分续费 1 个月`
                              : `用 ${plan.monthlyPriceCredits.toFixed(0)} 积分开通 1 个月`}
                        </Button>
                      )}
                    </div>
                  </SpotlightCard>
                );
              })}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}
