import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = {
  title: "FoliarcAdmin | 卷弧工坊独立运营后台",
  description:
    "FoliarcStudio 独立运营与工程治理后台（admin-java :8081 · 公共资料库 · 异步任务中心 · 会话链路追踪 · 计费与 VIP · Prompt 审计）",
};

export default function AdminRootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="zh-CN" className="dark h-full">
      <body className="min-h-full bg-background text-foreground">{children}</body>
    </html>
  );
}
