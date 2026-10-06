import { cn } from "@/lib/utils";

/**
 * The OCTO mark (the hub-and-spokes symbol from public/Mesta_Logo_*.png), drawn
 * as a vector so it stays sharp at 16–64px and takes `currentColor` — one
 * asset for light and dark. Decorative by default; pass `title` when the mark
 * stands alone as the only label.
 */
export function OctoMark({ className, title }: { className?: string; title?: string }) {
  return (
    <svg viewBox="96 40 500 430" fill="currentColor" role={title ? "img" : undefined} aria-hidden={title ? undefined : true} aria-label={title} className={cn("shrink-0", className)}>
      <g stroke="currentColor" strokeWidth="13" strokeLinecap="round" fill="none">
        <path d="M108 309 L408 311" />
        <path d="M335 56 L446 246" />
        <path d="M610 95 L520 246" />
        <path d="M568 311 L590 311" />
        <path d="M398 457 L440 384" />
        <path d="M520 374 L534 398" />
      </g>
      <path d="M446 246 Q484 268 520 246 Q512 290 568 311 Q512 330 520 374 Q484 352 440 384 Q438 332 400 311 Q438 290 446 246 Z" />
    </svg>
  );
}
