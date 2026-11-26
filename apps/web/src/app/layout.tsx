import type { Metadata } from "next";
import { AuthProvider } from "@/components/auth-provider";
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
  title: "FoliarcStudio | 卷弧工坊",
  description:
    "企业级长篇小说创作 AI Agent 平台（Next.js + Java 21 Spring Boot + Python FastAPI）",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
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
      <body className="flex min-h-full flex-col bg-background text-foreground">
        <UiProviders>
          <AuthProvider>{children}</AuthProvider>
        </UiProviders>
      </body>
    </html>
  );
}
