"use client";

import {
  createContext,
  useCallback,
  useContext,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Dialog } from "@base-ui/react/dialog";
import { Button } from "./button";
import { useToast } from "./toast";

export type ConfirmCopy = {
  title: string;
  description: string;
  confirmLabel: string;
  cancelLabel?: string;
  variant?: "default" | "destructive";
};

export type ConfirmRequest = ConfirmCopy & {
  onConfirm: () => void | Promise<void>;
  returnFocus?: () => HTMLElement | null;
};

export type ConfirmResult = "succeeded" | "dismissed" | "failed" | "busy";
export type ConfirmFn = (request: ConfirmRequest) => Promise<ConfirmResult>;

export type ConfirmDialogProps = ConfirmCopy & {
  open: boolean;
  pending: boolean;
  onAccept: () => void;
  onDismiss: () => void;
  returnFocus?: () => HTMLElement | null;
};

export function ConfirmDialog({
  title,
  description,
  confirmLabel,
  cancelLabel = "取消",
  variant = "default",
  open,
  pending,
  onAccept,
  onDismiss,
  returnFocus,
}: ConfirmDialogProps) {
  const cancelRef = useRef<HTMLButtonElement | null>(null);
  const isDestructive = variant === "destructive";

  return (
    <Dialog.Root
      open={open}
      modal
      onOpenChange={(nextOpen) => {
        if (!nextOpen) onDismiss();
      }}
    >
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-50 bg-black/50 backdrop-blur-[1px]" />
        <Dialog.Popup
          role={isDestructive ? "alertdialog" : "dialog"}
          initialFocus={cancelRef}
          finalFocus={() => returnFocus?.() ?? true}
          className="fixed top-1/2 left-1/2 z-50 w-[min(26rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 rounded-xl border border-border bg-surface-elevated p-5 text-foreground shadow-2xl outline-none"
        >
          <Dialog.Title className="text-sm font-semibold text-foreground">
            {title}
          </Dialog.Title>
          <Dialog.Description className="mt-2 text-xs leading-relaxed text-muted-foreground">
            {description}
          </Dialog.Description>
          <div className="mt-5 flex items-center justify-end gap-2">
            <Button
              ref={cancelRef}
              variant="outline"
              size="sm"
              onClick={onDismiss}
            >
              {cancelLabel}
            </Button>
            <Button
              variant={isDestructive ? "destructive" : "default"}
              size="sm"
              loading={pending}
              onClick={onAccept}
            >
              {pending ? "处理中..." : confirmLabel}
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

interface ActiveConfirm {
  request: ConfirmRequest;
  resolve: (result: ConfirmResult) => void;
}

function sanitizeConfirmError(err: unknown): string {
  const fallback = "操作未能完成，请稍后重试";
  if (typeof err !== "object" || err === null) return fallback;
  const candidate = err as { status?: number; message?: string };
  if (candidate.status === 401) return "登录状态已过期，请重新登录";
  if (candidate.status === 403) return "当前账号没有此操作权限";
  if (candidate.status === 404) return "目标记录不存在或已被删除";
  if (candidate.status === 409) return "内容已在别处更新，请刷新后重试";
  if (candidate.status === 429) return "操作过于频繁，请稍后再试";
  const raw =
    typeof candidate.message === "string"
      ? candidate.message.replace(/^\s*\[[A-Z0-9_ -]{2,40}\]\s*/, "").trim()
      : "";
  if (
    !raw ||
    raw.length > 140 ||
    !/[\u4e00-\u9fff]/.test(raw) ||
    /<!doctype|<html|exception|traceback|sqlstate|localhost:|:(?:3000|3001|8000|8080|8081)|\b(?:admin-java|biz-java|agent-py|rabbitmq|minio|milvus|postgres|redis|outbox)\b|failed to fetch|networkerror|\bhttp[_\s-]*\d{3}\b/i.test(
      raw,
    )
  ) {
    return fallback;
  }
  return raw;
}

const ConfirmContext = createContext<ConfirmFn | null>(null);

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const toast = useToast();
  const [active, setActive] = useState<ActiveConfirm | null>(null);
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const activeRef = useRef<ActiveConfirm | null>(null);
  const inFlightRef = useRef(false);

  const confirm = useCallback<ConfirmFn>((request) => {
    if (activeRef.current || inFlightRef.current) {
      return Promise.resolve("busy");
    }
    return new Promise<ConfirmResult>((resolve) => {
      const next: ActiveConfirm = { request, resolve };
      activeRef.current = next;
      setActive(next);
      setPending(false);
      setOpen(true);
    });
  }, []);

  const handleDismiss = useCallback(() => {
    setOpen(false);
    if (inFlightRef.current) {
      // Request already issued: allow hiding presentation; promise settles when mutation finishes.
      return;
    }
    const current = activeRef.current;
    if (current) {
      activeRef.current = null;
      setActive(null);
      current.resolve("dismissed");
    }
  }, []);

  const handleAccept = useCallback(() => {
    const current = activeRef.current;
    if (!current || inFlightRef.current) return;
    inFlightRef.current = true;
    setPending(true);
    Promise.resolve()
      .then(() => current.request.onConfirm())
      .then(() => {
        setOpen(false);
        setPending(false);
        inFlightRef.current = false;
        activeRef.current = null;
        setActive(null);
        current.resolve("succeeded");
      })
      .catch((err: unknown) => {
        console.error("[ConfirmAction]", err);
        setOpen(false);
        setPending(false);
        inFlightRef.current = false;
        activeRef.current = null;
        setActive(null);
        toast.error(sanitizeConfirmError(err));
        current.resolve("failed");
      });
  }, [toast]);

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {active && (
        <ConfirmDialog
          open={open}
          pending={pending}
          title={active.request.title}
          description={active.request.description}
          confirmLabel={active.request.confirmLabel}
          cancelLabel={active.request.cancelLabel}
          variant={active.request.variant}
          returnFocus={active.request.returnFocus}
          onAccept={handleAccept}
          onDismiss={handleDismiss}
        />
      )}
    </ConfirmContext.Provider>
  );
}

export function useConfirm(): ConfirmFn {
  const ctx = useContext(ConfirmContext);
  if (!ctx) {
    throw new Error("useConfirm must be used within ConfirmProvider");
  }
  return ctx;
}
