"use client";

import { createContext, useContext, useMemo, type ReactNode } from "react";
import { Toast } from "@base-ui/react/toast";
import { X } from "lucide-react";
import { cn } from "cn";

export type ToastOptions = {
  id?: string;
  description?: string;
  timeout?: number;
};

export type ToastFn = (title: string, options?: ToastOptions) => string;

export type ToastAPI = {
  success: ToastFn;
  error: ToastFn;
  warning: ToastFn;
  info: ToastFn;
  dismiss: (id: string) => void;
};

type ToastKind = "success" | "error" | "warning" | "info";

const TOAST_STYLES: Record<ToastKind, string> = {
  success: "border-status-success-border bg-status-success-bg text-status-success",
  error: "border-status-danger-border bg-status-danger-bg text-status-danger",
  warning: "border-status-warning-border bg-status-warning-bg text-status-warning",
  info: "border-status-info-border bg-status-info-bg text-status-info",
};

const ToastContext = createContext<ToastAPI | null>(null);

function ToastHost({ children }: { children: ReactNode }) {
  const manager = Toast.useToastManager<{ kind?: ToastKind }>();

  const api = useMemo<ToastAPI>(() => {
    function emit(kind: ToastKind, title: string, options?: ToastOptions): string {
      const isError = kind === "error";
      return manager.add({
        id: options?.id,
        title,
        description: options?.description,
        type: kind,
        priority: isError ? "high" : "low",
        timeout: options?.timeout ?? (isError ? 8000 : 5000),
        data: { kind },
      });
    }
    return {
      success: (title, opts) => emit("success", title, opts),
      error: (title, opts) => emit("error", title, opts),
      warning: (title, opts) => emit("warning", title, opts),
      info: (title, opts) => emit("info", title, opts),
      dismiss: (id) => manager.close(id),
    };
  }, [manager]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <Toast.Portal>
        <Toast.Viewport className="fixed right-4 bottom-4 z-[60] flex w-88 max-w-[calc(100vw-2rem)] flex-col gap-2 outline-none">
          {manager.toasts.map((item) => {
            const kind = (item.data?.kind ?? item.type ?? "info") as ToastKind;
            const style = TOAST_STYLES[kind] ?? TOAST_STYLES.info;
            return (
              <Toast.Root
                key={item.id}
                toast={item}
                className={cn(
                  "flex items-start justify-between gap-2 rounded-xl border px-3.5 py-2.5 text-xs shadow-lg transition-all data-[limited]:hidden",
                  style,
                )}
              >
                <Toast.Content className="min-w-0 flex-1 space-y-0.5">
                  {item.title && (
                    <Toast.Title className="font-semibold leading-snug">
                      {item.title}
                    </Toast.Title>
                  )}
                  {item.description && (
                    <Toast.Description className="text-[11px] opacity-90">
                      {item.description}
                    </Toast.Description>
                  )}
                </Toast.Content>
                <Toast.Close
                  aria-label="关闭通知"
                  className="shrink-0 rounded p-0.5 opacity-80 hover:opacity-100 focus-visible:outline-2 focus-visible:outline-ring"
                >
                  <X className="size-3.5" />
                </Toast.Close>
              </Toast.Root>
            );
          })}
        </Toast.Viewport>
      </Toast.Portal>
    </ToastContext.Provider>
  );
}

export function ToastProvider({ children }: { children: ReactNode }) {
  return (
    <Toast.Provider limit={3}>
      <ToastHost>{children}</ToastHost>
    </Toast.Provider>
  );
}

export function useToast(): ToastAPI {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    throw new Error("useToast must be used within ToastProvider");
  }
  return ctx;
}
