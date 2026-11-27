"use client";

import type { ComponentProps } from "react";
import { Dialog as BaseDialog } from "@base-ui/react/dialog";
import { cn } from "cn";

export const Dialog = BaseDialog.Root;
export const DialogTrigger = BaseDialog.Trigger;
export const DialogTitle = BaseDialog.Title;
export const DialogDescription = BaseDialog.Description;
export const DialogClose = BaseDialog.Close;

export function DialogBackdrop() {
  return (
    <BaseDialog.Backdrop className="fixed inset-0 z-50 bg-black/45 backdrop-blur-[1px] transition-opacity duration-150 data-[starting-style]:opacity-0 data-[ending-style]:opacity-0 motion-reduce:transition-none" />
  );
}

export function DialogContent({ className, ...props }: Omit<BaseDialog.Popup.Props, "className"> & { className?: string }) {
  return (
    <BaseDialog.Portal>
      <DialogBackdrop />
      <BaseDialog.Popup
        className={cn(
          "fixed top-1/2 left-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-lg -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-xl border border-border bg-surface-elevated p-5 text-foreground shadow-2xl outline-none",
          "transition-[opacity,scale] duration-150 data-[starting-style]:scale-95 data-[starting-style]:opacity-0 data-[ending-style]:scale-95 data-[ending-style]:opacity-0 motion-reduce:transition-none",
          className,
        )}
        {...props}
      />
    </BaseDialog.Portal>
  );
}

export function DialogHeader({ className, ...props }: ComponentProps<"div">) {
  return <div className={cn("flex flex-col gap-1", className)} {...props} />;
}

export function DialogFooter({ className, ...props }: ComponentProps<"div">) {
  return <div className={cn("flex items-center justify-end gap-2", className)} {...props} />;
}
