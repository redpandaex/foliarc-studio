"use client";

import type { PointerEvent } from "react";
import { cn } from "cn";
import { Card, type CardProps } from "@/components/ui/card";

function moveSpotlight(event: PointerEvent<HTMLDivElement>) {
  if (event.pointerType === "touch" || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const card = event.currentTarget;
  const bounds = card.getBoundingClientRect();
  card.style.setProperty("--spotlight-x", `${event.clientX - bounds.left}px`);
  card.style.setProperty("--spotlight-y", `${event.clientY - bounds.top}px`);
}

export function SpotlightCard({ children, className, ...props }: CardProps) {
  return (
    <Card
      {...props}
      className={cn("foliarc-spotlight relative isolate", className)}
      onPointerMove={moveSpotlight}
    >
      <span aria-hidden="true" className="pointer-events-none absolute inset-0 -z-1 overflow-hidden rounded-[inherit]" />
      {children}
    </Card>
  );
}
