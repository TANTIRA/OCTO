"use client";

import { ArrowDownRight, ArrowUpRight, Minus } from "lucide-react";
import { cn } from "@/lib/utils";
import { useFormat } from "@/lib/use-format";

export type TrendSemantic = "positive" | "negative" | "neutral";

/**
 * Signed change. Colour follows whether the move is *good*, not its sign (a
 * falling cost is green). An explicit `trend` semantic wins over inference
 * (V2 CC-003); otherwise `upIsGood` decides. The arrow shows direction; text
 * says favourable/unfavourable for screen readers so colour is never alone.
 */
export function Delta({ value, unit = "%", upIsGood, trend, pill, className, digits }: { value: number; unit?: "%" | "pts" | "×" | "$" | ""; upIsGood?: boolean; trend?: TrendSemantic; pill?: boolean; className?: string; digits?: number }) {
  const f = useFormat();
  const dir = value > 0 ? "up" : value < 0 ? "down" : "flat";
  const good = trend ? (trend === "neutral" ? undefined : trend === "positive") : upIsGood === undefined || dir === "flat" ? undefined : (dir === "up") === upIsGood;
  const Arrow = dir === "up" ? ArrowUpRight : dir === "down" ? ArrowDownRight : Minus;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-0.5 whitespace-nowrap text-[12px] font-medium tabular-nums",
        good === true && "text-ok",
        good === false && "text-danger",
        good === undefined && "text-ink-3",
        pill && "h-[22px] rounded-full px-2 leading-none",
        pill && good === true && "bg-mark-ok/10",
        pill && good === false && "bg-mark-danger/10",
        pill && good === undefined && "bg-sunken",
        className,
      )}
    >
      <Arrow aria-hidden className="size-3.5" />
      {f.delta(value, unit, digits)}
      {good !== undefined && <span className="sr-only">{good ? " (favourable)" : " (unfavourable)"}</span>}
    </span>
  );
}

/** TrendBadge (V2 shared primitive): the pill form of Delta used on KPI cards and drawers. */
export function TrendBadge(props: Omit<React.ComponentProps<typeof Delta>, "pill">) {
  return <Delta {...props} pill />;
}
