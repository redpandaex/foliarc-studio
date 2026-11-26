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

export default function RegisterPage() {
  const router = useRouter();
  const { user, loading, register } = useAuth();
  const [email, setEmail] = useState("");
  const [username, setUsername] = useState("");
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
      await register(email, username, password);
      router.push("/books");
    } catch (err) {
      if (err instanceof ApiClientError) {
        const detailMsg = Object.values(err.details).join("；");
        setError(detailMsg || err.message);
      } else {
        setError("注册失败，请检查 biz-java 与 Postgres/Redis 是否已启动");
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
          <h1 className="text-2xl font-bold tracking-tight">创建作者账号</h1>
          <p className="text-sm text-muted-foreground">
            注册后即可创建个人书架、编写章节并管理版本快照
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <label htmlFor="username" className="text-xs font-medium text-muted-foreground">
              笔名 / 用户名
            </label>
            <Input
              id="username"
              type="text"
              required
              minLength={2}
              maxLength={64}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="例如：墨客"
              className="h-9 text-sm"
            />
          </div>

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
              minLength={8}
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="至少 8 位字符"
              className="h-9 text-sm"
            />
          </div>

          {error && (
            <Alert status="danger" announce="assertive">
              {error}
            </Alert>
          )}

          <Button type="submit" size="lg" className="w-full" loading={submitting}>
            {submitting ? "注册中..." : "注册并进入工作台"}
          </Button>
        </form>

        <div className="mt-5 flex items-center justify-between text-xs text-muted-foreground">
          <Link href="/" className="hover:underline">
            ← 返回系统探针
          </Link>
          <Link href="/login" className="font-medium text-foreground hover:underline">
            已有账号？直接登录
          </Link>
        </div>
      </Card>
    </main>
  );
}
