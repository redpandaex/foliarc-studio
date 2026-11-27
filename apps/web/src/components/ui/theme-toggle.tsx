"use client";

import { useSyncExternalStore } from "react";
import { Palette } from "lucide-react";
import { Button } from "./button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "./dropdown-menu";
import { themeConfig, type AppThemeId } from "@/lib/theme-config";

const THEME_CHANGE_EVENT = "foliarc-theme-change";
const ALLOWED_THEMES = Object.keys(themeConfig.labels) as AppThemeId[];

function isValidTheme(value: string | null | undefined): value is AppThemeId {
  return Boolean(value && ALLOWED_THEMES.includes(value as AppThemeId));
}

function readStoredTheme(): AppThemeId {
  if (typeof window === "undefined") return themeConfig.fallback;
  const attr = document.documentElement.dataset.theme;
  if (isValidTheme(attr)) return attr;
  try {
    const saved = window.localStorage.getItem(themeConfig.key);
    if (isValidTheme(saved)) return saved;
  } catch {
    // ignore storage restriction
  }
  return themeConfig.fallback;
}

function applyTheme(next: AppThemeId) {
  document.documentElement.dataset.theme = next;
  try {
    window.localStorage.setItem(themeConfig.key, next);
  } catch {
    // ignore storage restriction
  }
  window.dispatchEvent(new Event(THEME_CHANGE_EVENT));
}

function subscribeTheme(onStoreChange: () => void): () => void {
  const handleStorage = (event: StorageEvent) => {
    if (event.key !== themeConfig.key) return;
    const next = isValidTheme(event.newValue)
      ? event.newValue
      : themeConfig.fallback;
    document.documentElement.dataset.theme = next;
    onStoreChange();
  };
  window.addEventListener(THEME_CHANGE_EVENT, onStoreChange);
  window.addEventListener("storage", handleStorage);
  return () => {
    window.removeEventListener(THEME_CHANGE_EVENT, onStoreChange);
    window.removeEventListener("storage", handleStorage);
  };
}

export function ThemeToggle({ className }: { className?: string }) {
  const currentTheme = useSyncExternalStore<AppThemeId>(
    subscribeTheme,
    readStoredTheme,
    () => themeConfig.fallback,
  );

  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger render={<Button size="sm" variant="ghost" className={className} />}>
        <Palette className="size-3.5 text-primary" />
        <span>主题</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" aria-label="切换外观主题" className="w-36">
        <DropdownMenuRadioGroup value={currentTheme} onValueChange={applyTheme}>
          {ALLOWED_THEMES.map((id) => (
            <DropdownMenuRadioItem key={id} value={id}>
              {themeConfig.labels[id]}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
