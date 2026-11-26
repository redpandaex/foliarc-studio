"use client";

import { Select as BaseSelect } from "@base-ui/react/select";
import { Check, ChevronDown } from "lucide-react";
import { cn } from "cn";

export interface SelectOption<T extends string = string> {
  value: T;
  label: string;
  disabled?: boolean;
}

export interface SelectProps<T extends string = string> {
  id: string;
  label: string;
  name?: string;
  value: T | null;
  options: readonly SelectOption<T>[];
  onValueChange: (value: T | null) => void;
  placeholder?: string;
  disabled?: boolean;
  required?: boolean;
  describedBy?: string;
  invalid?: boolean;
  className?: string;
  hideLabel?: boolean;
}

export function Select<T extends string = string>({
  id,
  label,
  name,
  value,
  options,
  onValueChange,
  placeholder = "请选择...",
  disabled = false,
  required = false,
  describedBy,
  invalid = false,
  className,
  hideLabel = false,
}: SelectProps<T>) {
  const selectedOption = options.find((opt) => opt.value === value);

  return (
    <div data-slot="select" className={cn("flex flex-col gap-1", className)}>
      <label
        htmlFor={id}
        className={
          hideLabel
            ? "sr-only"
            : "text-xs font-medium text-muted-foreground"
        }
      >
        {label}
      </label>
      <BaseSelect.Root<T>
        name={name}
        value={value}
        disabled={disabled}
        required={required}
        onValueChange={(next) => onValueChange(next)}
      >
        <BaseSelect.Trigger
          id={id}
          aria-describedby={describedBy}
          aria-invalid={invalid || undefined}
          className={cn(
            "flex h-8 w-full items-center justify-between gap-2 rounded-lg border border-input bg-card px-2.5 text-left text-xs text-foreground transition-colors hover:bg-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-status-danger",
          )}
        >
          <BaseSelect.Value className="truncate">
            {() =>
              selectedOption ? (
                <span>{selectedOption.label}</span>
              ) : (
                <span className="text-muted-foreground">{placeholder}</span>
              )
            }
          </BaseSelect.Value>
          <BaseSelect.Icon className="shrink-0 text-muted-foreground">
            <ChevronDown className="size-3.5" />
          </BaseSelect.Icon>
        </BaseSelect.Trigger>
        <BaseSelect.Portal>
          <BaseSelect.Positioner sideOffset={4} className="z-50">
            <BaseSelect.Popup className="max-h-64 min-w-[var(--anchor-width)] overflow-y-auto rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-lg">
              <BaseSelect.List className="space-y-0.5">
                {options.map((opt) => (
                  <BaseSelect.Item
                    key={opt.value}
                    value={opt.value}
                    disabled={opt.disabled}
                    className="flex cursor-pointer items-center justify-between gap-2 rounded-md px-2.5 py-1.5 text-xs text-foreground outline-none select-none data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50"
                  >
                    <BaseSelect.ItemText className="truncate">
                      {opt.label}
                    </BaseSelect.ItemText>
                    <BaseSelect.ItemIndicator className="shrink-0 text-primary">
                      <Check className="size-3.5" />
                    </BaseSelect.ItemIndicator>
                  </BaseSelect.Item>
                ))}
              </BaseSelect.List>
            </BaseSelect.Popup>
          </BaseSelect.Positioner>
        </BaseSelect.Portal>
      </BaseSelect.Root>
    </div>
  );
}
