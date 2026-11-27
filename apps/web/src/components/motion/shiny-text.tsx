import type { ReactNode } from "react";

export function ShinyText({ children }: { children: ReactNode }) {
  return <span className="foliarc-shiny-text truncate">{children}</span>;
}
