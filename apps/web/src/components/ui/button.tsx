import { Button as ButtonPrimitive } from "@base-ui/react/button";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "cn";

const buttonVariants = cva(
  "group/button inline-flex shrink-0 items-center justify-center rounded-lg border border-transparent bg-clip-padding text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring active:not-aria-[haspopup]:translate-y-px disabled:pointer-events-none disabled:opacity-50 aria-busy:pointer-events-none aria-busy:opacity-75 [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  {
    variants: {
      variant: {
        default:
          "bg-primary text-primary-foreground shadow-2xs hover:brightness-95 dark:hover:brightness-110",
        outline:
          "border-border bg-card text-foreground shadow-2xs hover:bg-muted aria-expanded:bg-muted",
        secondary:
          "bg-muted text-foreground hover:bg-primary-soft hover:text-primary-soft-foreground aria-expanded:bg-primary-soft",
        ghost:
          "text-muted-foreground hover:bg-muted hover:text-foreground aria-expanded:bg-muted",
        destructive:
          "border-status-danger-border bg-status-danger-bg text-status-danger hover:brightness-95 dark:hover:brightness-110",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        default:
          "h-8 gap-1.5 px-3 text-xs has-data-[icon=inline-end]:pr-2 has-data-[icon=inline-start]:pl-2",
        xs: "h-6 gap-1 rounded-[min(var(--radius-md),10px)] px-2 text-xs [&_svg:not([class*='size-'])]:size-3",
        sm: "h-7 gap-1.5 rounded-[min(var(--radius-md),12px)] px-2.5 text-xs [&_svg:not([class*='size-'])]:size-3.5",
        lg: "h-9 gap-1.5 px-4 text-sm",
        icon: "size-8",
        "icon-xs": "size-6 rounded-[min(var(--radius-md),10px)] [&_svg:not([class*='size-'])]:size-3",
        "icon-sm": "size-7 rounded-[min(var(--radius-md),12px)]",
        "icon-lg": "size-9",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  },
);

export type ButtonProps = ButtonPrimitive.Props &
  VariantProps<typeof buttonVariants> & {
    loading?: boolean;
  };

function Button({
  className,
  variant = "default",
  size = "default",
  loading = false,
  disabled,
  type = "button",
  ...props
}: ButtonProps) {
  return (
    <ButtonPrimitive
      data-slot="button"
      type={type}
      disabled={Boolean(disabled || loading)}
      aria-busy={loading || undefined}
      className={
        typeof className === "function"
          ? (state) => cn(buttonVariants({ variant, size }), className(state))
          : cn(buttonVariants({ variant, size }), className)
      }
      {...props}
    />
  );
}

export { Button, buttonVariants };
