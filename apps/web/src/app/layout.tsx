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
  title: "卷弧工坊 · FoliarcStudio",
  description: "长篇小说创作工作台",
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
        <link rel="preconnect" href="https://fontsapi.zeoseven.com" crossOrigin="anonymous" />
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
