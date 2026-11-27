"use client";

import { Dialog as BaseDialog } from "@base-ui/react/dialog";
import { DialogBackdrop, DialogHeader } from "./dialog";
import { cn } from "cn";

export const Sheet = BaseDialog.Root;
export const SheetTrigger = BaseDialog.Trigger;
export const SheetTitle = BaseDialog.Title;
export const SheetDescription = BaseDialog.Description;
export const SheetHeader = DialogHeader;
export const SheetClose = BaseDialog.Close;

export function SheetContent({
  side = "right",
  className,
  ...props
}: Omit<BaseDialog.Popup.Props, "className"> & { side?: "left" | "right"; className?: string }) {
  return (
    <BaseDialog.Portal>
      <DialogBackdrop />
      <BaseDialog.Popup
        className={cn(
          "fixed inset-y-0 z-50 flex w-96 max-w-full flex-col border-border bg-surface-elevated text-foreground shadow-2xl outline-none transition-transform duration-150 motion-reduce:transition-none",
          side === "right"
            ? "right-0 border-l data-[starting-style]:translate-x-full data-[ending-style]:translate-x-full"
            : "left-0 border-r data-[starting-style]:-translate-x-full data-[ending-style]:-translate-x-full",
          className,
        )}
        {...props}
      />
    </BaseDialog.Portal>
  );
}
