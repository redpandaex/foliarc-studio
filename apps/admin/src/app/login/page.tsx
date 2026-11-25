"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { ShieldCheck } from "lucide-react";
import {
  ADMIN_API_BASE,
  AdminApiError,
  adminAuthApi,
  setAdminToken,
} from "@/lib/admin-api";

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
      <div className="w-full max-w-md rounded-2xl border border-border bg-card p-7 shadow-xl">
        <div className="mb-6 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-blue-600/20 text-blue-400">
            <ShieldCheck className="h-5 w-5" />
          </div>
          <div>
            <h1 className="text-lg font-bold tracking-tight">FoliarcAdmin 独立运营后台</h1>
            <p className="font-mono text-[11px] text-muted-foreground">
              {ADMIN_API_BASE} · JWT aud=foliarc-admin
            </p>
          </div>
        </div>

        <p className="mb-5 rounded-lg border border-blue-500/20 bg-blue-500/10 px-3 py-2 text-xs text-blue-200">
          此入口与作者端（:3000 / :8080）物理隔离，仅允许具备 <code className="font-mono">ROLE_ADMIN</code>{" "}
          权限的管理员签发后台独立令牌登录。
        </p>

        {error && (
          <div className="mb-4 rounded-lg border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground">管理员邮箱</label>
            <input
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="admin@foliarc.studio"
              className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-blue-500"
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground">密码</label>
            <input
              type="password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="请输入管理员密码"
              className="w-full rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:border-blue-500"
            />
          </div>
          <button
            type="submit"
            disabled={submitting}
            className="w-full rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-blue-500 disabled:opacity-50"
          >
            {submitting ? "正在校验管理员权限..." : "登录运营后台"}
          </button>
        </form>
      </div>
    </main>
  );
}
