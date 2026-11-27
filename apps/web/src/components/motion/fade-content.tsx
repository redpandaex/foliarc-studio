import type { ReactNode } from "react";

export function FadeContent({ children, delayMs = 0 }: { children: ReactNode; delayMs?: number }) {
  return (
    <div
      className="motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-bottom-1 motion-safe:duration-300 motion-safe:fill-mode-backwards"
      style={{ animationDelay: `${Math.min(150, Math.max(0, delayMs))}ms` }}
    >
      {children}
    </div>
  );
}
