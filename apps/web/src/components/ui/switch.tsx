"use client";

import { Switch as BaseSwitch } from "@base-ui/react/switch";
import { cn } from "cn";

export function Switch({
  size = "md",
  className,
  ...props
}: Omit<BaseSwitch.Root.Props, "className"> & { size?: "sm" | "md"; className?: string }) {
  return (
    <BaseSwitch.Root
      nativeButton
      render={<button type="button" />}
      className={cn(
        "inline-flex shrink-0 cursor-pointer items-center rounded-full bg-muted ring-1 ring-inset ring-border transition-colors data-[checked]:bg-primary data-[disabled]:cursor-not-allowed data-[disabled]:opacity-50 motion-reduce:transition-none",
        size === "sm" ? "h-4 w-7" : "h-5 w-9",
        className,
      )}
      {...props}
    >
      <BaseSwitch.Thumb className={cn(
        "translate-x-0.5 rounded-full bg-card shadow-xs transition-transform motion-reduce:transition-none",
        size === "sm" ? "size-3 data-[checked]:translate-x-3.5" : "size-4 data-[checked]:translate-x-4.5",
      )} />
    </BaseSwitch.Root>
  );
}
