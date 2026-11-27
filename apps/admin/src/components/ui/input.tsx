"use client";

import { useState, type ComponentPropsWithRef } from "react";
import { Eye, EyeOff } from "lucide-react";
import { cn } from "cn";

export type InputProps = ComponentPropsWithRef<"input">;

const BASE_INPUT_CLASS =
  "flex h-8 w-full rounded-lg border border-input bg-card px-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-status-danger";

function PasswordInputField({
  className,
  disabled,
  ...props
}: Omit<InputProps, "type">) {
  const [visible, setVisible] = useState(false);

  return (
    <div className="relative w-full">
      <input
        data-slot="input"
        type={visible ? "text" : "password"}
        disabled={disabled}
        className={cn(
          BASE_INPUT_CLASS,
          "pr-9 [&::-ms-clear]:hidden [&::-ms-reveal]:hidden",
          className,
        )}
        {...props}
      />
      <button
        type="button"
        disabled={disabled}
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => setVisible((prev) => !prev)}
        aria-label={visible ? "隐藏密码" : "显示密码"}
        aria-pressed={visible}
        title={visible ? "隐藏密码" : "显示密码"}
        className="absolute inset-y-0 right-1 my-auto flex size-6 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-ring disabled:pointer-events-none disabled:opacity-50"
      >
        {visible ? (
          <EyeOff className="size-3.5" aria-hidden="true" />
        ) : (
          <Eye className="size-3.5" aria-hidden="true" />
        )}
      </button>
    </div>
  );
}

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

  if (type === "password") {
    return <PasswordInputField className={className} {...props} />;
  }

  return (
    <input
      data-slot="input"
      type={type}
      className={cn(BASE_INPUT_CLASS, className)}
      {...props}
    />
  );
}
