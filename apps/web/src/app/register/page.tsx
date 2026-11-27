"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { useAuth } from "@/components/auth-provider";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { ApiClientError } from "@/lib/api";
import { formatWebError, isSafeUserMessage } from "@/lib/error-messages";

interface FieldErrors {
  username?: string;
  email?: string;
  password?: string;
}

export default function RegisterPage() {
  const router = useRouter();
  const { user, loading, register } = useAuth();
  const [email, setEmail] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
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
    setFieldErrors({});
    setSubmitting(true);
    try {
      await register(email.trim(), username.trim(), password);
      router.push("/books");
    } catch (err) {
      if (err instanceof ApiClientError && err.details) {
        const nextFields: FieldErrors = {};
        for (const key of ["username", "email", "password"] as const) {
          const val = err.details[key];
          if (typeof val === "string" && isSafeUserMessage(val)) {
            nextFields[key] = val;
          }
        }
        if (Object.keys(nextFields).length > 0) {
          setFieldErrors(nextFields);
          setSubmitting(false);
          return;
        }
      }
      setError(formatWebError(err, "暂时无法注册，请稍后重试", { action: "register" }));
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
          <h1 className="text-xl font-bold tracking-tight">创建作者账号</h1>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <label htmlFor="username" className="text-xs font-medium text-muted-foreground">
              笔名
            </label>
            <Input
              id="username"
              type="text"
              required
              minLength={2}
              maxLength={64}
              value={username}
              onChange={(e) => {
                setUsername(e.target.value);
                if (fieldErrors.username) setFieldErrors((p) => ({ ...p, username: undefined }));
              }}
              placeholder="墨客"
              className="h-9 text-sm"
            />
            {fieldErrors.username && (
              <p role="alert" className="text-xs text-status-danger">
                {fieldErrors.username}
              </p>
            )}
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
              onChange={(e) => {
                setEmail(e.target.value);
                if (fieldErrors.email) setFieldErrors((p) => ({ ...p, email: undefined }));
              }}
              placeholder="name@example.com"
              className="h-9 text-sm"
            />
            {fieldErrors.email && (
              <p role="alert" className="text-xs text-status-danger">
                {fieldErrors.email}
              </p>
            )}
          </div>

          <div className="space-y-1.5">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <label htmlFor="password" className="font-medium">
                密码
              </label>
              <span>至少 8 位</span>
            </div>
            <Input
              id="password"
              type="password"
              required
              minLength={8}
              autoComplete="new-password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                if (fieldErrors.password) setFieldErrors((p) => ({ ...p, password: undefined }));
              }}
              placeholder="请输入密码"
              className="h-9 text-sm"
            />
            {fieldErrors.password && (
              <p role="alert" className="text-xs text-status-danger">
                {fieldErrors.password}
              </p>
            )}
          </div>

          {error && (
            <p role="alert" className="text-xs text-status-danger">
              {error}
            </p>
          )}

          <Button type="submit" size="lg" className="w-full" loading={submitting}>
            {submitting ? "注册中…" : "注册并进入书架"}
          </Button>
        </form>

        <div className="mt-5 flex items-center justify-between text-xs text-muted-foreground">
          <Link href="/" className="hover:text-foreground hover:underline">
            返回首页
          </Link>
          <Link href="/login" className="font-medium text-foreground hover:underline">
            已有账号？登录
          </Link>
        </div>
      </Card>
    </main>
  );
}
