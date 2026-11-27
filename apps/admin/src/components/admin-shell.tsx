"use client";

import { Button } from "@/components/ui/button";
import { ThemeToggle } from "@/components/ui/theme-toggle";

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
  Loader2,
  LogOut,
  ShieldCheck,
  Users,
} from "lucide-react";
import {
  AdminApiError,
  adminAuthApi,
  getAdminToken,
  setAdminToken,
  type AdminUserProfile,
} from "@/lib/admin-api";

const NAV_ITEMS = [
  { href: "/", label: "总览", icon: LayoutDashboard },
  { href: "/users", label: "用户与权限", icon: Users },
  { href: "/knowledge", label: "公共资料", icon: BookOpen },
  { href: "/jobs", label: "任务中心", icon: ListChecks },
  { href: "/traces", label: "会话追踪", icon: Activity },
  { href: "/billing", label: "计费与会员", icon: CreditCard },
  { href: "/prompts", label: "提示词与审计", icon: FileCode2 },
  { href: "/evals", label: "质量评测", icon: FlaskConical },
];

export function AdminShell({
  title,
  actions,
  children,
}: {
  title: string;
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
      <div className="flex min-h-screen items-center justify-center gap-2 bg-background text-xs text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin" />
        <span>加载中…</span>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      <aside className="flex w-56 shrink-0 flex-col border-r border-border bg-sidebar">
        <div className="flex items-center gap-2.5 border-b border-border px-5 py-4">
          <ShieldCheck className="h-5 w-5 text-primary" />
          <span className="font-semibold tracking-tight">管理后台</span>
        </div>

        <nav className="flex-1 space-y-1 p-3">
          {NAV_ITEMS.map((item) => {
            const Icon = item.icon;
            const active = pathname === item.href;
            return (
              <Link
                key={item.href}
                href={item.href}
                aria-current={active ? "page" : undefined}
                className={`flex items-center gap-2.5 rounded-lg px-3 py-2 text-xs font-medium transition-colors ${
                  active
                    ? "bg-accent text-accent-foreground"
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
          <div className="truncate font-medium text-foreground">
            {adminUser?.username ?? "管理员"}
          </div>
          {adminUser?.email && (
            <div className="mt-0.5 truncate text-[11px] text-muted-foreground">
              {adminUser.email}
            </div>
          )}
          <Button
            type="button"
            onClick={handleLogout}
            variant="outline"
            size="sm"
            className="mt-3 w-full"
          >
            <LogOut className="h-3.5 w-3.5" />
            退出登录
          </Button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex flex-wrap items-center justify-between gap-4 border-b border-border bg-card/60 px-6 py-3.5">
          <h1 className="text-base font-semibold tracking-tight">{title}</h1>
          <div className="flex flex-wrap items-center gap-2">
            {actions}
            <ThemeToggle />
          </div>
        </header>
        <main className="flex-1 space-y-6 p-6">{children}</main>
      </div>
    </div>
  );
}
