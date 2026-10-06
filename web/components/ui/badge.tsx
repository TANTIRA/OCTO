import Link from "next/link";
import { cn } from "@/lib/utils";

export type Tone = "neutral" | "accent" | "ok" | "warn" | "danger" | "info" | "ai";

/*
 * Badge system (parity BADGE-001/002, DS-007). One geometry for every status
 * pill: 22px tall, 11px text, 12px icon, never wraps. Text uses the AA-safe ink
 * tokens; the dot/icon uses the brighter semantic mark colour.
 */
const PILL: Record<Tone, string> = {
  neutral: "border-line bg-subtle text-ink-2",
  accent: "border-accent-line bg-accent-soft text-accent-ink",
  ok: "border-mark-ok/25 bg-mark-ok/8 text-ok",
  warn: "border-mark-warn/30 bg-mark-warn/8 text-warn",
  danger: "border-mark-danger/25 bg-mark-danger/8 text-danger",
  info: "border-mark-info/25 bg-mark-info/8 text-info",
  ai: "border-ai/25 bg-ai/8 text-ai",
};

const MARK: Record<Tone, string> = {
  neutral: "bg-mark-neutral text-mark-neutral",
  accent: "bg-accent text-accent",
  ok: "bg-mark-ok text-mark-ok",
  warn: "bg-mark-warn text-mark-warn",
  danger: "bg-mark-danger text-mark-danger",
  info: "bg-mark-info text-mark-info",
  ai: "bg-ai text-ai",
};

/** Status text never relies on colour alone: the label is always present (DS-007). */
export function StatusBadge({ tone = "neutral", dot = true, icon, className, children }: { tone?: Tone; dot?: boolean; icon?: React.ReactNode; className?: string; children: React.ReactNode }) {
  return (
    <span className={cn("inline-flex h-[22px] shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border px-2 text-[11px] font-medium leading-none tabular-nums", PILL[tone], className)}>
      {icon ? (
        <span aria-hidden className={cn("flex !bg-transparent [&>svg]:size-3", MARK[tone])}>
          {icon}
        </span>
      ) : (
        dot && <span aria-hidden className={cn("size-1.5 shrink-0 rounded-full", MARK[tone])} />
      )}
      {children}
    </span>
  );
}

/** Uppercase category tag. */
export function Tag({ tone = "neutral", className, children }: { tone?: Tone; className?: string; children: React.ReactNode }) {
  return <span className={cn("inline-flex h-5 shrink-0 items-center whitespace-nowrap rounded-xs px-1.5 text-[10px] font-semibold uppercase leading-none tracking-[0.06em]", PILL[tone], "border-0", className)}>{children}</span>;
}

/** Count badge — reserved for actionable counts. */
export function CountBadge({ children, tone = "neutral", className }: { children: React.ReactNode; tone?: "neutral" | "accent" | "danger"; className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full px-1.5 text-[10px] font-semibold tabular-nums leading-none",
        tone === "neutral" && "bg-sunken text-ink-2",
        tone === "accent" && "bg-accent-fill text-white",
        tone === "danger" && "bg-mark-danger/12 text-danger",
        className,
      )}
    >
      {children}
    </span>
  );
}

/** A typed reference to an ontology object: type label + name. Links drill down. */
export function EntityChip({ type, name, href, className }: { type: string; name: string; href?: string; className?: string }) {
  const body = (
    <>
      <span className="text-[10px] font-semibold uppercase tracking-[0.05em] text-ink-4">{type}</span>
      <span className="truncate text-ink">{name}</span>
    </>
  );
  const cls = cn("inline-flex h-[22px] max-w-full items-center gap-1.5 whitespace-nowrap rounded-sm border border-line bg-surface px-1.5 text-[12px] leading-none", className);
  return href ? (
    <Link href={href} className={cn(cls, "hover:border-accent-line hover:text-accent focus-visible:outline-2 focus-visible:outline-focus")}>
      {body}
    </Link>
  ) : (
    <span className={cls}>{body}</span>
  );
}

export function Kbd({ children, className }: { children: React.ReactNode; className?: string }) {
  return <kbd className={cn("inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-xs border border-line bg-subtle px-1 font-data text-[10px] text-ink-3", className)}>{children}</kbd>;
}

/** Square monogram used where a logo would sit (entity cells, object headers). */
export function Monogram({ name, size = "md", className }: { name: string; size?: "sm" | "md" | "lg"; className?: string }) {
  const initials = name
    .split(/\s+/)
    .filter((w) => /^[A-Za-z]/.test(w))
    .slice(0, 2)
    .map((w) => w[0])
    .join("")
    .toUpperCase();
  const hue = [...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);
  return (
    <span
      aria-hidden
      style={{ backgroundColor: `hsl(${hue} 70% 94%)`, color: `hsl(${hue} 60% 24%)` }}
      className={cn(
        "flex shrink-0 items-center justify-center rounded-md font-semibold",
        size === "sm" && "size-6 text-[10px]",
        size === "md" && "size-8 text-[11px]",
        size === "lg" && "size-12 rounded-lg text-[15px]",
        className,
      )}
    >
      {initials}
    </span>
  );
}
