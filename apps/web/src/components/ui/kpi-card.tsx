import type { ReactNode } from "react";
import { cn } from "cn";
import type { UiStatus } from "./badge";

const VALUE_TONE: Record<UiStatus, string> = {
  neutral: "text-foreground",
  success: "text-status-success",
  warning: "text-status-warning",
  danger: "text-status-danger",
  info: "text-status-info",
  ai: "text-status-ai",
  vip: "text-status-vip",
};

export interface KpiCardProps {
  label: string;
  value: ReactNode;
  detail?: ReactNode;
  status?: UiStatus;
  className?: string;
}

export function KpiCard({
  label,
  value,
  detail,
  status = "neutral",
  className,
}: KpiCardProps) {
  return (
    <dl
      data-slot="kpi-card"
      className={cn(
        "rounded-xl border border-border bg-card p-4 shadow-xs",
        className,
      )}
    >
      <dt className="text-xs font-medium text-muted-foreground">{label}</dt>
      <dd
        className={cn(
          "mt-1.5 font-mono text-2xl font-bold tracking-tight tabular-nums",
          VALUE_TONE[status],
        )}
      >
        {value}
      </dd>
      {detail && (
        <dd className="mt-1.5 text-[11px] text-muted-foreground">{detail}</dd>
      )}
    </dl>
  );
}
