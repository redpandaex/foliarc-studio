"use client";

import {
  useLayoutEffect,
  useRef,
  type ComponentPropsWithRef,
  type Ref,
} from "react";
import { cn } from "cn";

export type TextareaProps = ComponentPropsWithRef<"textarea"> & {
  variant?: "form" | "manuscript";
};

function assignRef<T>(ref: Ref<T> | undefined, value: T | null) {
  if (!ref) return;
  if (typeof ref === "function") {
    ref(value);
  } else {
    ref.current = value;
  }
}

function findScrollParent(node: HTMLElement | null): HTMLElement | null {
  let cur = node?.parentElement ?? null;
  while (cur) {
    const style = window.getComputedStyle(cur);
    if (style.overflowY === "auto" || style.overflowY === "scroll") {
      return cur;
    }
    cur = cur.parentElement;
  }
  return null;
}

export function Textarea({
  className,
  variant = "form",
  ref,
  value,
  defaultValue,
  ...props
}: TextareaProps) {
  const localRef = useRef<HTMLTextAreaElement | null>(null);

  useLayoutEffect(() => {
    if (variant !== "manuscript") return;
    const el = localRef.current;
    if (!el) return;

    const syncHeight = () => {
      const scrollParent = findScrollParent(el);
      const prevTop = scrollParent?.scrollTop ?? 0;
      el.style.height = "0px";
      const nextHeight = Math.max(el.scrollHeight, 280);
      el.style.height = `${nextHeight}px`;
      if (scrollParent) {
        scrollParent.scrollTop = prevTop;
      }
    };

    syncHeight();
    window.addEventListener("resize", syncHeight);
    return () => window.removeEventListener("resize", syncHeight);
  }, [variant, value, defaultValue]);

  const setMergedRef = (node: HTMLTextAreaElement | null) => {
    localRef.current = node;
    assignRef(ref, node);
  };

  if (variant === "manuscript") {
    return (
      <textarea
        ref={setMergedRef}
        data-slot="textarea"
        data-variant="manuscript"
        value={value}
        defaultValue={defaultValue}
        className={cn(
          "block min-h-[280px] w-full resize-none overflow-hidden bg-transparent font-editor text-editor tracking-[0.012em] text-foreground placeholder:text-muted-foreground/60 outline-none focus-visible:outline-none",
          className,
        )}
        {...props}
      />
    );
  }

  return (
    <textarea
      ref={setMergedRef}
      data-slot="textarea"
      data-variant="form"
      value={value}
      defaultValue={defaultValue}
      className={cn(
        "flex w-full resize-none rounded-lg border border-input bg-card px-3 py-2 text-xs leading-relaxed text-foreground placeholder:text-muted-foreground/75 transition-colors focus-visible:border-ring focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-status-danger",
        className,
      )}
      {...props}
    />
  );
}
