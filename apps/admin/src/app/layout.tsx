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
  title: "卷弧工坊 · 管理后台",
  description: "卷弧工坊运营与内容管理后台",
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
