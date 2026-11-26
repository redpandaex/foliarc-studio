"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { useAuth } from "@/components/auth-provider";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { ApiClientError } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const { user, loading, login } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!loading && user) {
      router.replace("/books");
    }
  }, [loading, user, router]);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(email, password);
      router.push("/books");
    } catch (err) {
      if (err instanceof ApiClientError) {
        setError(err.message);
      } else {
        setError("登录失败，请检查 biz-java 服务是否已启动");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="mx-auto flex w-full max-w-md flex-1 flex-col justify-center px-6 py-12">
      <div className="mb-3 flex justify-end">
        <ThemeToggle />
      </div>
      <Card surface="elevated" className="p-6">
        <div className="mb-6 space-y-1">
          <p className="font-mono text-xs text-muted-foreground">
            FoliarcStudio · 卷弧工坊
          </p>
          <h1 className="text-2xl font-bold tracking-tight">作者登录</h1>
          <p className="text-sm text-muted-foreground">
            Access Token 仅存内存 + HttpOnly Cookie 自动轮换刷新
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <label htmlFor="email" className="text-xs font-medium text-muted-foreground">
              邮箱
            </label>
            <Input
              id="email"
              type="email"
              required
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="author@foliarc.studio"
              className="h-9 text-sm"
            />
          </div>

          <div className="space-y-1.5">
            <label htmlFor="password" className="text-xs font-medium text-muted-foreground">
              密码
            </label>
            <Input
              id="password"
              type="password"
              required
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="请输入至少 8 位密码"
              className="h-9 text-sm"
            />
          </div>

          {error && (
            <Alert status="danger" announce="assertive">
              {error}
            </Alert>
          )}

          <Button type="submit" size="lg" className="w-full" loading={submitting}>
            {submitting ? "登录中..." : "登录进入书架"}
          </Button>
        </form>

        <div className="mt-5 flex items-center justify-between text-xs text-muted-foreground">
          <Link href="/" className="hover:underline">
            ← 返回系统探针
          </Link>
          <Link href="/register" className="font-medium text-foreground hover:underline">
            还没有账号？去注册
          </Link>
        </div>
      </Card>
    </main>
  );
}
