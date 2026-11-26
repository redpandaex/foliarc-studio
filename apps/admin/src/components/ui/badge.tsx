import type { ComponentPropsWithRef } from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "cn";

export type UiStatus =
  | "neutral"
  | "success"
  | "warning"
  | "danger"
  | "info"
  | "ai"
  | "vip";

const badgeVariants = cva(
  "inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] font-medium leading-tight whitespace-nowrap",
  {
    variants: {
      status: {
        neutral: "border-border bg-muted text-foreground",
        success: "border-status-success-border bg-status-success-bg text-status-success",
        warning: "border-status-warning-border bg-status-warning-bg text-status-warning",
        danger: "border-status-danger-border bg-status-danger-bg text-status-danger",
        info: "border-status-info-border bg-status-info-bg text-status-info",
        ai: "border-status-ai-border bg-status-ai-bg text-status-ai",
        vip: "border-status-vip-border bg-status-vip-bg text-status-vip",
      },
    },
    defaultVariants: {
      status: "neutral",
    },
  },
);

export type BadgeProps = ComponentPropsWithRef<"span"> &
  VariantProps<typeof badgeVariants>;

export function Badge({ className, status = "neutral", ...props }: BadgeProps) {
  return (
    <span
      data-slot="badge"
      className={cn(badgeVariants({ status }), className)}
      {...props}
    />
  );
}
