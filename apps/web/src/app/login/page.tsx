"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { useAuth } from "@/components/auth-provider";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { formatWebError } from "@/lib/error-messages";

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
      await login(email.trim(), password);
      router.push("/books");
    } catch (err) {
      setError(formatWebError(err, "暂时无法登录，请稍后重试", { action: "login" }));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center bg-background px-4">
      <div className="absolute right-4 top-4">
        <ThemeToggle />
      </div>
      <Card surface="elevated" className="w-full max-w-sm p-7">
        <div className="mb-6 space-y-1">
          <div className="text-xs font-medium text-muted-foreground">卷弧工坊</div>
          <h1 className="text-xl font-bold tracking-tight">作者登录</h1>
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
              onChange={(e) => {
                setEmail(e.target.value);
                if (error) setError(null);
              }}
              placeholder="name@example.com"
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
              onChange={(e) => {
                setPassword(e.target.value);
                if (error) setError(null);
              }}
              placeholder="请输入密码"
              className="h-9 text-sm"
            />
          </div>

          {error && (
            <p role="alert" className="text-xs text-status-danger">
              {error}
            </p>
          )}

          <Button type="submit" size="lg" className="w-full" loading={submitting}>
            {submitting ? "登录中…" : "登录"}
          </Button>
        </form>

        <div className="mt-5 flex items-center justify-between text-xs text-muted-foreground">
          <Link href="/" className="hover:text-foreground hover:underline">
            返回首页
          </Link>
          <Link href="/register" className="font-medium text-foreground hover:underline">
            注册账号
          </Link>
        </div>
      </Card>
    </main>
  );
}
