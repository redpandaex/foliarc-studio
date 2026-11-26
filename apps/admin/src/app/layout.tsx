import type { Metadata } from "next";
import type { ReactNode } from "react";
import { UiProviders } from "@/components/ui/providers";
import { themeConfig } from "@/lib/theme-config";
import "./globals.css";

const themeInit = `(() => {
  try {
    const saved = localStorage.getItem(${JSON.stringify(themeConfig.key)});
    const allowed = ${JSON.stringify(Object.keys(themeConfig.labels))};
    if (saved && allowed.includes(saved)) document.documentElement.dataset.theme = saved;
  } catch {}
})();`;

export const metadata: Metadata = {
  title: "FoliarcAdmin | 卷弧工坊独立运营后台",
  description:
    "FoliarcStudio 独立运营与工程治理后台（admin-java :8081 · 公共资料库 · 异步任务中心 · 会话链路追踪 · 计费与 VIP · Prompt 审计 · Golden Eval）",
};

export default function AdminRootLayout({ children }: { children: ReactNode }) {
  return (
    <html
      lang="zh-CN"
      data-theme={themeConfig.fallback}
      suppressHydrationWarning
      className="h-full antialiased"
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeInit }} />
      </head>
      <body className="min-h-full bg-background text-foreground">
        <UiProviders>{children}</UiProviders>
      </body>
    </html>
  );
}
