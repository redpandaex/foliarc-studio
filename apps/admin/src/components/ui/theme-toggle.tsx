"use client";

import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Check, Palette } from "lucide-react";
import { cn } from "cn";
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
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    function onPointerDown(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  return (
    <div ref={containerRef} className={cn("relative inline-block", className)}>
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="inline-flex h-7 items-center gap-1.5 rounded-lg border border-transparent bg-transparent px-2.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
      >
        <Palette className="size-3.5 text-primary" />
        <span>主题</span>
      </button>
      {open && (
        <div
          role="menu"
          aria-label="切换外观主题"
          className="absolute right-0 top-9 z-50 w-36 rounded-xl border border-border bg-popover p-1 text-popover-foreground shadow-lg"
        >
          {ALLOWED_THEMES.map((id) => {
            const active = id === currentTheme;
            return (
              <button
                key={id}
                type="button"
                role="menuitemradio"
                aria-checked={active}
                onClick={() => {
                  applyTheme(id);
                  setOpen(false);
                }}
                className={cn(
                  "flex w-full items-center justify-between gap-2 rounded-lg px-2.5 py-1.5 text-left text-xs transition-colors",
                  active
                    ? "bg-primary-soft font-medium text-primary-soft-foreground"
                    : "text-foreground hover:bg-muted",
                )}
              >
                <span>{themeConfig.labels[id]}</span>
                {active && <Check className="size-3.5 shrink-0 text-primary" />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
