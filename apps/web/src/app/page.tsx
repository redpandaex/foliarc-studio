"use client";

import Link from "next/link";
import { useState } from "react";
import { BookOpenText, GitBranch, Network, Sparkles } from "lucide-react";
import { useAuth } from "@/components/auth-provider";
import { ServiceStatus } from "@/components/service-status";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { ThemeToggle } from "@/components/ui/theme-toggle";

const WORKSPACE_PILLARS = [
  { icon: BookOpenText, label: "卷章正文", tag: "专注写作" },
  { icon: Network, label: "人物关系图谱", tag: "防剧透视角" },
  { icon: GitBranch, label: "剧情泳道与伏笔", tag: "全书脉络" },
  { icon: Sparkles, label: "AI 协同写作", tag: "长程记忆" },
];

export default function HomePage() {
  const { user, loading } = useAuth();
  const [showStatus, setShowStatus] = useState(false);

  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground">
      <header className="border-b border-border/60">
        <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-6">
          <span className="font-semibold tracking-tight">卷弧工坊</span>
          <div className="flex items-center gap-2">
            <ThemeToggle />
            {!loading && user ? (
              <Link href="/books" className={buttonVariants({ size: "sm" })}>
                进入书架
              </Link>
            ) : (
              <>
                <Link
                  href="/login"
                  className={buttonVariants({ variant: "outline", size: "sm" })}
                >
                  登录
                </Link>
                <Link href="/register" className={buttonVariants({ size: "sm" })}>
                  注册
                </Link>
              </>
            )}
          </div>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-4xl flex-1 flex-col items-center justify-center gap-10 px-6 py-16">
        <section className="flex max-w-xl flex-col items-center space-y-5 text-center">
          <h1 className="text-4xl font-bold tracking-tight sm:text-5xl">卷弧工坊</h1>
          <p className="text-base text-muted-foreground">长篇小说创作工作台</p>
          <div className="flex flex-wrap items-center justify-center gap-3 pt-1">
            {!loading && user ? (
              <Link href="/books" className={buttonVariants({ size: "lg" })}>
                进入书架
              </Link>
            ) : (
              <>
                <Link href="/register" className={buttonVariants({ size: "lg" })}>
                  创建账号
                </Link>
                <Link
                  href="/login"
                  className={buttonVariants({ variant: "outline", size: "lg" })}
                >
                  登录
                </Link>
              </>
            )}
          </div>
        </section>

        <section className="grid w-full gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {WORKSPACE_PILLARS.map((item) => {
            const Icon = item.icon;
            return (
              <Card
                key={item.label}
                className="flex flex-col items-start gap-2.5 p-4"
              >
                <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-accent text-primary">
                  <Icon className="h-4 w-4" />
                </div>
                <div>
                  <div className="text-sm font-semibold">{item.label}</div>
                  <div className="mt-0.5 text-xs text-muted-foreground">{item.tag}</div>
                </div>
              </Card>
            );
          })}
        </section>
      </main>

      <footer className="border-t border-border/60">
        <div className="mx-auto flex max-w-5xl flex-col gap-3 px-6 py-4">
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>FoliarcStudio</span>
            <Button
              type="button"
              variant="ghost"
              size="xs"
              aria-expanded={showStatus}
              onClick={() => setShowStatus((v) => !v)}
            >
              {showStatus ? "收起服务状态" : "服务状态"}
            </Button>
          </div>
          {showStatus && <ServiceStatus />}
        </div>
      </footer>
    </div>
  );
}
