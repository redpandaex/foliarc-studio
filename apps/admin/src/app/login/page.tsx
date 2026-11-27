"use client";

import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ThemeToggle } from "@/components/ui/theme-toggle";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { ShieldCheck } from "lucide-react";
import { adminAuthApi, setAdminToken } from "@/lib/admin-api";
import { formatAdminError } from "@/lib/error-messages";

export default function AdminLoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const res = await adminAuthApi.login(email.trim(), password);
      setAdminToken(res.accessToken);
      router.replace("/");
    } catch (err) {
      setError(formatAdminError(err, "暂时无法登录，请稍后重试", { action: "login" }));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="absolute right-4 top-4">
        <ThemeToggle />
      </div>
      <Card surface="elevated" className="w-full max-w-sm p-7">
        <div className="mb-6 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-accent text-primary">
            <ShieldCheck className="h-5 w-5" />
          </div>
          <h1 className="text-lg font-bold tracking-tight">管理后台</h1>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <label htmlFor="email" className="text-xs font-medium text-muted-foreground">
              管理员邮箱
            </label>
            <Input
              id="email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => {
                setEmail(e.target.value);
                if (error) setError(null);
              }}
              placeholder="name@example.com"
            />
          </div>
          <div className="space-y-1.5">
            <label htmlFor="password" className="text-xs font-medium text-muted-foreground">
              密码
            </label>
            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
                if (error) setError(null);
              }}
              placeholder="请输入密码"
            />
          </div>

          {error && (
            <p role="alert" className="text-xs text-status-danger">
              {error}
            </p>
          )}

          <Button type="submit" loading={submitting} variant="default" size="sm" className="w-full">
            {submitting ? "登录中…" : "登录"}
          </Button>
        </form>
      </Card>
    </main>
  );
}
