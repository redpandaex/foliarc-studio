"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { useToast } from "@/components/ui/toast";
import {
  ApiClientError,
  vipApi,
  type MyVipStatus,
  type VipPlanItem,
} from "@/lib/api";

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
  if (bps >= 10000) return "标准费率";
  return `${(bps / 1000).toFixed(1)} 折创作消耗`;
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

  useEffect(() => {
    if (authLoading) return;
    if (!user) {
      router.replace("/login");
      return;
    }
    let cancelled = false;
    Promise.all([vipApi.listPlans(), vipApi.me()])
      .then(([planList, myVip]) => {
        if (!cancelled) {
          setPlans(planList);
          setStatus(myVip);
          setError(null);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof ApiClientError ? err.message : "加载会员权益失败");
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
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
        `已成功开通「${res.displayName}」（${res.durationDays} 天），额外获赠 ${res.grantedBonusCredits.toFixed(0)} 创作积分。`,
      );
      const latest = await vipApi.me();
      setStatus(latest);
    } catch (err) {
      toast.error(err instanceof ApiClientError ? err.message : "开通会员失败");
    } finally {
      setSubmittingCode(null);
    }
  }

  if (authLoading || (!user && loading)) {
    return (
      <main className="mx-auto flex max-w-5xl flex-1 items-center justify-center px-6 py-16 text-sm text-muted-foreground">
        正在加载会员权益...
      </main>
    );
  }

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="border-b border-border bg-card">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <Link href="/books" className="text-xs font-medium text-muted-foreground hover:text-foreground">
              ← 返回书架
            </Link>
            <span className="text-muted-foreground">/</span>
            <h1 className="text-sm font-semibold">创作会员与权益</h1>
          </div>
          <div className="flex items-center gap-2">
            <ThemeToggle />
            <Link href="/library" className={buttonVariants({ variant: "outline", size: "sm" })}>
              我的资料库
            </Link>
            <Link href="/books" className={buttonVariants({ variant: "outline", size: "sm" })}>
              作品列表
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
                <Badge status="vip" className="font-mono">
                  {status.planCode}
                </Badge>
              </div>
              <div className="text-[11px] text-muted-foreground">
                {status.expiresAt
                  ? `有效期至 ${new Date(status.expiresAt).toLocaleDateString("zh-CN")}`
                  : "永久有效 · 随时可升级"}
              </div>
            </div>

            <div className="space-y-1">
              <div className="text-xs text-muted-foreground">个人资料库用量</div>
              <div className="text-base font-semibold tabular-nums">
                {status.libraryUsage.usedDocs} / {status.libraryUsage.maxDocs} 份
              </div>
              <div className="text-[11px] text-muted-foreground tabular-nums">
                空间 {formatBytes(status.libraryUsage.usedBytes)} / {formatBytes(status.libraryUsage.maxTotalBytes)}
              </div>
            </div>

            <div className="space-y-1">
              <div className="text-xs text-muted-foreground">长篇记忆与改稿跨度</div>
              <div className="text-base font-semibold tabular-nums">
                最高 {status.entitlements.maxMemoryChapters.toLocaleString()} 章
              </div>
              <div className="text-[11px] text-muted-foreground tabular-nums">
                单次连环改稿 ≤ {status.entitlements.rippleMaxRangeChapters.toLocaleString()} 章
              </div>
            </div>

            <div className="space-y-1">
              <div className="text-xs text-muted-foreground">可用创作积分</div>
              <div className="font-mono text-base font-semibold tabular-nums">
                {status.balanceCredits.toFixed(1)}
              </div>
              <div className="text-[11px] text-muted-foreground">
                {formatDiscount(status.creditDiscountBps)}
              </div>
            </div>
          </Card>
        )}

        {error && (
          <Alert status="danger" announce="assertive">
            {error}
          </Alert>
        )}

        <section className="space-y-3">
          <div>
            <h2 className="text-lg font-bold tracking-tight">选择适合你创作规模的方案</h2>
            <p className="text-xs text-muted-foreground">
              不同档位动态解锁更大的个人资料库容量、更长的长篇记忆规模、连环改稿范围与更高并发速率。开通即赠送当月创作积分。
            </p>
          </div>

          {loading ? (
            <p className="py-10 text-center text-xs text-muted-foreground">正在加载套餐...</p>
          ) : (
            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
              {plans.map((plan) => {
                const isCurrent = status?.planCode === plan.planCode;
                const e = plan.entitlements;
                return (
                  <Card
                    key={plan.planCode}
                    surface={isCurrent ? "elevated" : "card"}
                    className={`flex flex-col justify-between p-5 ${
                      isCurrent ? "border-status-vip-border" : ""
                    }`}
                  >
                    <div className="space-y-3">
                      <div className="flex items-center justify-between">
                        <h3 className="text-base font-bold">{plan.displayName}</h3>
                        {isCurrent && <Badge status="vip">当前方案</Badge>}
                      </div>
                      <div className="text-2xl font-bold tracking-tight tabular-nums">
                        {formatPrice(plan.monthlyPriceCents)}
                      </div>
                      <p className="text-[11px] text-muted-foreground">
                        月赠 {plan.monthlyGrantCredits.toFixed(0)} 积分 · {formatDiscount(plan.creditDiscountBps)}
                      </p>

                      <ul className="space-y-1.5 border-t border-border pt-3 text-xs text-muted-foreground">
                        <li>• 长篇记忆规模：最高 {e.maxMemoryChapters.toLocaleString()} 章</li>
                        <li>• 单节正文上限：{(e.maxSectionChars / 10000).toFixed(0)} 万字</li>
                        <li>
                          • 个人资料库：{e.userLibraryMaxDocs} 份 / {formatBytes(e.userLibraryMaxTotalBytes)}
                        </li>
                        <li>
                          • 连环改稿跨度：
                          {e.rippleAllowFullBook
                            ? "最高 2,000 章 / 全书扫描"
                            : `单次 ≤ ${e.rippleMaxRangeChapters} 章`}
                        </li>
                        <li>• 单批补丁上限：{e.rippleMaxPatchChapters} 章</li>
                        <li>• 创作速率：{e.rpmLimit} 次/分</li>
                      </ul>
                    </div>

                    <div className="mt-5 pt-2">
                      {plan.planCode === "FREE" ? (
                        <Button variant="outline" size="sm" className="w-full" disabled>
                          {isCurrent ? "正在使用" : "默认基础方案"}
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
                            ? "开通中..."
                            : isCurrent
                              ? `续费 1 个月 (${plan.monthlyPriceCredits.toFixed(0)} 积分)`
                              : `积分开通 (${plan.monthlyPriceCredits.toFixed(0)} 积分)`}
                        </Button>
                      )}
                    </div>
                  </Card>
                );
              })}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}
