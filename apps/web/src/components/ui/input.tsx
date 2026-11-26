import type { ComponentPropsWithRef } from "react";
import { cn } from "cn";

export type InputProps = ComponentPropsWithRef<"input">;

export function Input({ className, type = "text", ...props }: InputProps) {
  if (type === "checkbox" || type === "radio") {
    return (
      <input
        data-slot="input"
        type={type}
        className={cn(
          "size-4 shrink-0 rounded border border-input bg-card accent-primary focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:cursor-not-allowed disabled:opacity-50",
          className,
        )}
        {...props}
      />
    );
  }

  if (type === "file") {
    return (
      <input
        data-slot="input"
        type="file"
        className={cn(
          "block w-full rounded-lg border border-input bg-card px-2.5 py-1.5 text-xs text-foreground file:mr-3 file:rounded-md file:border file:border-border file:bg-muted file:px-2.5 file:py-1 file:text-xs file:font-medium file:text-foreground hover:file:bg-accent hover:file:text-accent-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:cursor-not-allowed disabled:opacity-50",
          className,
        )}
        {...props}
      />
    );
  }

  return (
    <input
      data-slot="input"
      type={type}
      className={cn(
        "flex h-8 w-full rounded-lg border border-input bg-card px-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-status-danger",
        className,
      )}
      {...props}
    />
  );
}
