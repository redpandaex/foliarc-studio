"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { useAuth } from "@/components/auth-provider";
import { Button } from "@/components/ui/button";
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
      <div className="rounded-xl border border-border bg-card p-6 shadow-xs">
        <div className="mb-6 space-y-1">
          <p className="font-mono text-xs text-muted-foreground">
            InkFlow Studio · 墨流工坊
          </p>
          <h1 className="text-2xl font-bold tracking-tight">创建作者账号</h1>
          <p className="text-sm text-muted-foreground">
            注册后即可创建个人书架、编写章节并管理版本快照
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <label htmlFor="username" className="text-sm font-medium">
              笔名 / 用户名
            </label>
            <input
              id="username"
              type="text"
              required
              minLength={2}
              maxLength={64}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="例如：墨客"
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
            />
          </div>

          <div className="space-y-1.5">
            <label htmlFor="email" className="text-sm font-medium">
              邮箱
            </label>
            <input
              id="email"
              type="email"
              required
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="author@inkflow.studio"
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
            />
          </div>

          <div className="space-y-1.5">
            <label htmlFor="password" className="text-sm font-medium">
              密码
            </label>
            <input
              id="password"
              type="password"
              required
              minLength={8}
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="至少 8 位字符"
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
            />
          </div>

          {error && (
            <div className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
              {error}
            </div>
          )}

          <Button type="submit" className="w-full" disabled={submitting}>
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
      </div>
    </main>
  );
}
