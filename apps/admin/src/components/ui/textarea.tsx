import type { ComponentPropsWithRef } from "react";
import { cn } from "cn";

export type TextareaProps = ComponentPropsWithRef<"textarea"> & {
  variant?: "form" | "manuscript";
};

export function Textarea({
  className,
  variant = "form",
  ...props
}: TextareaProps) {
  if (variant === "manuscript") {
    return (
      <textarea
        data-slot="textarea"
        data-variant="manuscript"
        className={cn(
          "mx-auto min-h-0 w-full max-w-editor flex-1 resize-none bg-transparent font-editor text-editor text-foreground placeholder:text-muted-foreground outline-none focus-visible:outline-none",
          className,
        )}
        {...props}
      />
    );
  }

  return (
    <textarea
      data-slot="textarea"
      data-variant="form"
      className={cn(
        "flex w-full rounded-lg border border-input bg-card px-3 py-2 text-xs leading-relaxed text-foreground placeholder:text-muted-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-status-danger",
        className,
      )}
      {...props}
    />
  );
}
