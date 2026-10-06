import { useId } from "react";
import { cn } from "@/lib/utils";

/** Inline trend with a soft gradient fill (Vestra sparkline). Decorative next to a labelled value. */
export function Sparkline({ values, tone = "accent", className }: { values: number[]; tone?: "accent" | "gain" | "warn" | "loss" | "muted"; className?: string }) {
  const id = useId();
  if (values.length < 2) return null;
  const w = 96;
  const h = 32;
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const y = (v: number) => h - 2 - ((v - lo) / (hi - lo || 1)) * (h - 6);
  const step = w / (values.length - 1);
  const pts = values.map((v, i) => [i * step, y(v)] as const);
  const line = pts.map(([x, yy], i) => `${i ? "L" : "M"}${x.toFixed(1)},${yy.toFixed(1)}`).join(" ");
  const color = { accent: "var(--color-accent)", gain: "var(--color-gain)", warn: "var(--color-mark-warn)", loss: "var(--color-loss)", muted: "var(--color-ink-4)" }[tone];
  return (
    <svg aria-hidden viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className={cn("h-8 w-24 overflow-visible", className)}>
      <defs>
        <linearGradient id={id} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.22" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${line} L${w},${h} L0,${h} Z`} fill={`url(#${id})`} />
      <path d={line} fill="none" stroke={color} strokeWidth="1.5" vectorEffect="non-scaling-stroke" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}
