"use client";

import { Tooltip as BaseTooltip } from "@base-ui/react/tooltip";
import { cn } from "cn";

export const Tooltip = BaseTooltip.Root;
export const TooltipTrigger = BaseTooltip.Trigger;
export const TooltipProvider = BaseTooltip.Provider;

type ContentProps = Omit<BaseTooltip.Popup.Props, "className"> &
  Pick<BaseTooltip.Positioner.Props, "side" | "align" | "sideOffset"> & { className?: string };

export function TooltipContent({
  side = "top",
  align = "center",
  sideOffset = 6,
  className,
  ...props
}: ContentProps) {
  return (
    <BaseTooltip.Portal>
      <BaseTooltip.Positioner side={side} align={align} sideOffset={sideOffset} className="z-50">
        <BaseTooltip.Popup
          className={cn(
            "max-w-64 rounded-lg border border-border bg-popover px-2 py-1 text-xs text-popover-foreground shadow-md",
            "origin-[var(--transform-origin)] transition-[opacity,transform,scale] duration-150 data-[starting-style]:scale-95 data-[starting-style]:opacity-0 data-[ending-style]:scale-95 data-[ending-style]:opacity-0 motion-reduce:transition-none",
            className,
          )}
          {...props}
        />
      </BaseTooltip.Positioner>
    </BaseTooltip.Portal>
  );
}
