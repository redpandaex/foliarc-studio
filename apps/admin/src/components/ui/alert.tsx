import type { ReactNode } from "react";
import { cva } from "class-variance-authority";
import { cn } from "cn";
import type { UiStatus } from "./badge";

const alertVariants = cva(
  "flex flex-wrap items-center justify-between gap-3 rounded-lg border px-3.5 py-2.5 text-xs",
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

export interface AlertProps {
  status: UiStatus;
  title?: string;
  children: ReactNode;
  action?: ReactNode;
  announce?: "off" | "polite" | "assertive";
  className?: string;
}

export function Alert({
  status,
  title,
  children,
  action,
  announce = "off",
  className,
}: AlertProps) {
  return (
    <div
      data-slot="alert"
      role={announce === "assertive" ? "alert" : announce === "polite" ? "status" : undefined}
      aria-live={announce}
      className={cn(alertVariants({ status }), className)}
    >
      <div className="min-w-0 flex-1 space-y-0.5">
        {title && <div className="font-semibold">{title}</div>}
        <div>{children}</div>
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}
