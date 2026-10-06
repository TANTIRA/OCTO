"use client";

import { forwardRef } from "react";
import Link from "next/link";
import { Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

/** Shared focus treatment for every interactive control (plan §29). */
export const ring = "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus";
export const ringInset = "focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-focus";

type Variant = "primary" | "secondary" | "ghost" | "danger" | "link";
type Size = "xs" | "sm" | "md" | "lg";

/*
 * Variants follow the Vestra control language (token-registry.md):
 * primary = blue fill, faint white border and inset top highlight;
 * secondary = near-white fill with a hairline; ghost = text only.
 */
const VARIANT: Record<Variant, string> = {
  primary:
    "border border-white/20 bg-accent-fill text-white shadow-[inset_0_1px_0_rgb(255_255_255/0.35)] hover:bg-accent-hover active:translate-y-px disabled:bg-accent-fill/50 disabled:shadow-none",
  /* V2 PQ-003: white outlined — #cbd5e1 border, hover #f9fafb fill with #94a3b8 border (tokenised for dark). */
  secondary: "border border-line-strong bg-surface text-ink hover:border-line-hover hover:bg-subtle active:translate-y-px disabled:text-ink-4",
  ghost: "text-ink-2 hover:bg-hover hover:text-ink disabled:text-ink-4",
  danger: "border border-danger/30 bg-surface text-danger hover:bg-danger/8 disabled:opacity-50",
  link: "h-auto px-0 text-accent underline-offset-2 hover:underline disabled:text-ink-4",
};

/* CTRL-001: one size family, 6px control radius; md is the 36px default action height. */
const SIZE: Record<Size, string> = {
  xs: "h-6 px-2 text-[11px] gap-1 rounded-xs",
  sm: "h-8 px-3 text-[12px] gap-1.5 rounded-sm",
  md: "h-9 px-3.5 text-[13px] gap-2 rounded-sm",
  lg: "h-10 px-4 text-sm gap-2 rounded-sm",
};
const ICON_SIZE: Record<Size, string> = { xs: "size-6 rounded-xs", sm: "size-8 rounded-sm", md: "size-9 rounded-sm", lg: "size-10 rounded-sm" };

export type ButtonProps = React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: Size; loading?: boolean };

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "secondary", size = "md", loading, disabled, className, type = "button", children, ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={cn(
        "inline-flex shrink-0 cursor-pointer items-center justify-center whitespace-nowrap font-medium tracking-[-0.01em] transition-[background-color,color,transform] duration-150 ease-standard disabled:cursor-not-allowed [&_svg]:size-3.5 [&_svg]:shrink-0",
        SIZE[size],
        VARIANT[variant],
        ring,
        className,
      )}
      {...props}
    >
      {loading && <Loader2 aria-hidden className="animate-spin" />}
      {children}
    </button>
  );
});

/** Icon-only control. `label` is required and becomes the accessible name. */
export const IconButton = forwardRef<HTMLButtonElement, Omit<ButtonProps, "children"> & { label: string; icon: React.ReactNode }>(function IconButton(
  { label, icon, variant = "ghost", size = "md", className, type = "button", ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      aria-label={label}
      title={label}
      className={cn(
        "inline-flex shrink-0 cursor-pointer items-center justify-center transition-colors duration-150 ease-standard disabled:cursor-not-allowed disabled:opacity-50 [&_svg]:size-4",
        VARIANT[variant === "link" ? "ghost" : variant],
        ICON_SIZE[size],
        ring,
        className,
      )}
      {...props}
    >
      {icon}
    </button>
  );
});

export function ButtonGroup({ children, className }: { children: React.ReactNode; className?: string }) {
  return <div className={cn("flex items-center gap-2", className)}>{children}</div>;
}

/** Class string for anything that should look like a button (links, labels). */
export function buttonClass(variant: Variant = "secondary", size: Size = "md", className?: string) {
  return cn(
    "inline-flex shrink-0 cursor-pointer items-center justify-center whitespace-nowrap font-medium tracking-[-0.01em] transition-[background-color,color] duration-150 ease-standard [&_svg]:size-3.5 [&_svg]:shrink-0",
    SIZE[size],
    VARIANT[variant],
    ring,
    className,
  );
}

/** Navigation that looks like a button — never nest a link inside a <button>. */
export function LinkButton({ href, variant = "secondary", size = "md", className, children, ...props }: Omit<React.ComponentProps<typeof Link>, "className"> & { variant?: Variant; size?: Size; className?: string }) {
  return (
    <Link href={href} className={buttonClass(variant, size, className)} {...props}>
      {children}
    </Link>
  );
}
