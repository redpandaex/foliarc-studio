"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import {
  Activity,
  BookOpen,
  CreditCard,
  FileCode2,
  FlaskConical,
  LayoutDashboard,
  ListChecks,
  LogOut,
  ShieldCheck,
} from "lucide-react";
import {
  ADMIN_API_BASE,
  AdminApiError,
  adminAuthApi,
  getAdminToken,
  setAdminToken,
  type AdminUserProfile,
} from "@/lib/admin-api";

const NAV_ITEMS = [
  { href: "/", label: "总览大盘", icon: LayoutDashboard },
  { href: "/knowledge", label: "公共资料库", icon: BookOpen },
  { href: "/jobs", label: "异步任务中心", icon: ListChecks },
  { href: "/traces", label: "会话链路追踪", icon: Activity },
  { href: "/billing", label: "计费与 VIP", icon: CreditCard },
  { href: "/prompts", label: "Prompt 与审计", icon: FileCode2 },
  { href: "/evals", label: "Golden Eval 评测", icon: FlaskConical },
];

export function AdminShell({
  title,
  subtitle,
  actions,
  children,
}: {
  title: string;
  subtitle?: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  const pathname = usePathname();
  const router = useRouter();
  const [adminUser, setAdminUser] = useState<AdminUserProfile | null>(null);
  const [checking, setChecking] = useState(true);

  useEffect(() => {
    const token = getAdminToken();
    if (!token) {
      router.replace("/login");
      return;
    }
    let active = true;
    adminAuthApi
      .me()
      .then((profile) => {
        if (!active) return;
        setAdminUser(profile);
        setChecking(false);
      })
      .catch((err: unknown) => {
        if (!active) return;
        if (err instanceof AdminApiError && (err.status === 401 || err.status === 403)) {
          setAdminToken(null);
          router.replace("/login");
          return;
        }
        setChecking(false);
      });
    return () => {
      active = false;
    };
  }, [router]);

  function handleLogout() {
    adminAuthApi.logout();
    router.replace("/login");
  }

  if (checking) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background text-sm text-muted-foreground">
        校验管理员独立会话 (aud=foliarc-admin)...
      </div>
    );
  }

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      <aside className="flex w-60 shrink-0 flex-col border-r border-border bg-card">
        <div className="border-b border-border px-5 py-4">
          <div className="flex items-center gap-2">
            <ShieldCheck className="h-5 w-5 text-blue-400" />
            <span className="font-semibold tracking-tight">FoliarcAdmin</span>
          </div>
          <p className="mt-1 font-mono text-[10px] text-muted-foreground">
            OPS CONSOLE · :3001 → :8081
          </p>
        </div>

        <nav className="flex-1 space-y-1 p-3">
          {NAV_ITEMS.map((item) => {
            const Icon = item.icon;
            const active = pathname === item.href;
            return (
              <Link
                key={item.href}
                href={item.href}
                className={`flex items-center gap-2.5 rounded-lg px-3 py-2 text-xs font-medium transition-colors ${
                  active
                    ? "bg-blue-600/20 text-blue-300 ring-1 ring-blue-500/40"
                    : "text-muted-foreground hover:bg-muted hover:text-foreground"
                }`}
              >
                <Icon className="h-4 w-4" />
                <span>{item.label}</span>
              </Link>
            );
          })}
        </nav>

        <div className="border-t border-border p-3 text-xs">
          <div className="mb-2 truncate font-medium text-foreground">
            {adminUser?.username ?? "Admin"}
          </div>
          <div className="mb-3 truncate font-mono text-[10px] text-muted-foreground">
            {adminUser?.email ?? ADMIN_API_BASE}
          </div>
          <button
            type="button"
            onClick={handleLogout}
            className="flex w-full items-center justify-center gap-1.5 rounded-md border border-border bg-muted/40 px-3 py-1.5 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
          >
            <LogOut className="h-3.5 w-3.5" />
            退出运营后台
          </button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex flex-wrap items-center justify-between gap-4 border-b border-border bg-card/60 px-6 py-4">
          <div>
            <h1 className="text-lg font-semibold tracking-tight">{title}</h1>
            {subtitle && <p className="text-xs text-muted-foreground">{subtitle}</p>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </header>
        <main className="flex-1 space-y-6 p-6">{children}</main>
      </div>
    </div>
  );
}
