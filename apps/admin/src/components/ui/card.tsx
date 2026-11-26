import type { ComponentPropsWithRef } from "react";
import { cn } from "cn";

export type CardProps = ComponentPropsWithRef<"div"> & {
  surface?: "card" | "elevated";
};

export function Card({
  className,
  surface = "card",
  ...props
}: CardProps) {
  return (
    <div
      data-slot="card"
      data-surface={surface}
      className={cn(
        "rounded-xl border border-border text-card-foreground shadow-xs",
        surface === "elevated" ? "bg-surface-elevated" : "bg-card",
        className,
      )}
      {...props}
    />
  );
}
