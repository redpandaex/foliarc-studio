"use client";

import { Menu } from "@base-ui/react/menu";
import { Check } from "lucide-react";
import { cn } from "cn";

export const DropdownMenu = Menu.Root;
export const DropdownMenuTrigger = Menu.Trigger;

type ContentProps = Omit<Menu.Popup.Props, "className"> &
  Pick<Menu.Positioner.Props, "side" | "align" | "sideOffset"> & { className?: string };

export function DropdownMenuContent({
  side = "bottom",
  align = "start",
  sideOffset = 6,
  className,
  ...props
}: ContentProps) {
  return (
    <Menu.Portal>
      <Menu.Positioner side={side} align={align} sideOffset={sideOffset} className="z-50">
        <Menu.Popup
          className={cn(
            "rounded-xl border border-border bg-popover p-1 text-popover-foreground shadow-lg outline-none",
            "origin-[var(--transform-origin)] transition-[opacity,transform,scale] duration-150 data-[starting-style]:scale-95 data-[starting-style]:opacity-0 data-[ending-style]:scale-95 data-[ending-style]:opacity-0 motion-reduce:transition-none",
            className,
          )}
          {...props}
        />
      </Menu.Positioner>
    </Menu.Portal>
  );
}

const itemStyle =
  "flex w-full cursor-pointer items-center justify-between gap-2 rounded-lg px-2.5 py-1.5 text-left text-xs outline-none select-none data-[highlighted]:bg-primary-soft data-[highlighted]:text-primary-soft-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50";

export function DropdownMenuItem({
  className,
  variant = "default",
  ...props
}: Omit<Menu.Item.Props, "className"> & { className?: string; variant?: "default" | "destructive" }) {
  return (
    <Menu.Item
      className={cn(
        itemStyle,
        variant === "destructive" && "text-status-danger data-[highlighted]:bg-status-danger-bg data-[highlighted]:text-status-danger",
        className,
      )}
      {...props}
    />
  );
}

export function DropdownMenuSeparator({ className, ...props }: Omit<Menu.Separator.Props, "className"> & { className?: string }) {
  return <Menu.Separator className={cn("my-1 h-px bg-border", className)} {...props} />;
}

export const DropdownMenuGroup = Menu.Group;

export function DropdownMenuLabel({ className, ...props }: Omit<Menu.GroupLabel.Props, "className"> & { className?: string }) {
  return <Menu.GroupLabel className={cn("px-2.5 py-1 text-xs font-medium text-muted-foreground", className)} {...props} />;
}

export const DropdownMenuRadioGroup = Menu.RadioGroup;

export function DropdownMenuRadioItem({
  children,
  className,
  ...props
}: Omit<Menu.RadioItem.Props, "className"> & { className?: string }) {
  return (
    <Menu.RadioItem closeOnClick className={cn(itemStyle, "data-[checked]:bg-primary-soft data-[checked]:text-primary-soft-foreground", className)} {...props}>
      {children}
      <Menu.RadioItemIndicator><Check className="size-3.5 text-primary" /></Menu.RadioItemIndicator>
    </Menu.RadioItem>
  );
}
