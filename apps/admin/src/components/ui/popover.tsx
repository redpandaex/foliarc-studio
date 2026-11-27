"use client";

import { Popover as BasePopover } from "@base-ui/react/popover";
import { cn } from "cn";

export const Popover = BasePopover.Root;
export const PopoverTrigger = BasePopover.Trigger;
export const PopoverClose = BasePopover.Close;

type ContentProps = Omit<BasePopover.Popup.Props, "className"> &
  Pick<BasePopover.Positioner.Props, "side" | "align" | "sideOffset"> & { className?: string };

export function PopoverContent({
  side = "bottom",
  align = "center",
  sideOffset = 6,
  className,
  ...props
}: ContentProps) {
  return (
    <BasePopover.Portal>
      <BasePopover.Positioner side={side} align={align} sideOffset={sideOffset} className="z-50">
        <BasePopover.Popup
          className={cn(
            "rounded-xl border border-border bg-popover p-3 text-popover-foreground shadow-lg outline-none",
            "origin-[var(--transform-origin)] transition-[opacity,transform,scale] duration-150 data-[starting-style]:scale-95 data-[starting-style]:opacity-0 data-[ending-style]:scale-95 data-[ending-style]:opacity-0 motion-reduce:transition-none",
            className,
          )}
          {...props}
        />
      </BasePopover.Positioner>
    </BasePopover.Portal>
  );
}
