"use client";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ThemeToggle } from "@/components/ui/theme-toggle";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { ShieldCheck } from "lucide-react";
import { ADMIN_API_BASE, AdminApiError, adminAuthApi, setAdminToken } from "@/lib/admin-api";

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
      if (err instanceof AdminApiError) {
        setError(`[${err.code}] ${err.message}`);
      } else {
        setError(err instanceof Error ? err.message : "管理员登录失败");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="absolute right-4 top-4">
        <ThemeToggle />
      </div>
      <Card surface="elevated" className="w-full max-w-md p-7">
        <div className="mb-6 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-accent text-primary">
            <ShieldCheck className="h-5 w-5" />
          </div>
          <div>
            <h1 className="text-lg font-bold tracking-tight">FoliarcAdmin 独立运营后台</h1>
            <p className="font-mono text-[11px] text-muted-foreground">
              {ADMIN_API_BASE} · JWT aud=foliarc-admin
            </p>
          </div>
        </div>

        <Alert status="info" className="mb-5">
          此入口与作者端（:3000 / :8080）物理隔离，仅允许具备{" "}
          <code className="font-mono">ROLE_ADMIN</code> 权限的管理员签发后台独立令牌登录。
        </Alert>

        {error && (
          <Alert status="danger" announce="assertive" className="mb-4">
            {error}
          </Alert>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <label htmlFor="email" className="text-xs font-medium text-muted-foreground">
              管理员邮箱
            </label>
            <Input
              id="email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="admin@foliarc.studio"
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
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="请输入管理员密码"
            />
          </div>
          <Button type="submit" loading={submitting} variant="default" size="sm" className="w-full">
            {submitting ? "正在校验管理员权限..." : "登录运营后台"}
          </Button>
        </form>
      </Card>
    </main>
  );
}
