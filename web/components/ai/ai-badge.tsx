import { BadgeCheck, FileSearch, HelpCircle, Sparkles, UserCheck, Clock } from "lucide-react";
import { cn } from "@/lib/utils";
import type { Verification } from "@/lib/demo";

/*
 * Verification vocabulary (plan §25). AI output must never look like an
 * approved source value: AI states use the violet `ai` colour and a sparkle,
 * verified values use neutral/ok styling.
 */
const V: Record<Verification, { label: string; cls: string; icon: React.ReactNode }> = {
  verified: { label: "Verified", cls: "border-mark-ok/25 bg-mark-ok/8 text-ok", icon: <BadgeCheck /> },
  "source-derived": { label: "Source-derived", cls: "border-line bg-subtle text-ink-2", icon: <FileSearch /> },
  "ai-suggested": { label: "AI-suggested", cls: "border-ai/25 bg-ai/8 text-ai", icon: <Sparkles /> },
  "human-reviewed": { label: "Human-reviewed", cls: "border-mark-ok/25 bg-mark-ok/8 text-ok", icon: <UserCheck /> },
  "pending-review": { label: "Pending review", cls: "border-ai/25 bg-ai/8 text-ai", icon: <Clock /> },
  unsupported: { label: "Evidence required", cls: "border-mark-warn/30 bg-mark-warn/8 text-warn", icon: <HelpCircle /> },
};

export function VerificationBadge({ state, className }: { state: Verification; className?: string }) {
  const v = V[state];
  return (
    <span className={cn("inline-flex h-[22px] shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border px-2 text-[11px] font-medium leading-none [&_svg]:size-3", v.cls, className)}>
      {v.icon}
      {v.label}
    </span>
  );
}

export function AiBadge({ className }: { className?: string }) {
  return <VerificationBadge state="ai-suggested" className={className} />;
}

/** Three-step confidence meter with a text label. */
export function AiConfidence({ level }: { level: "High" | "Medium" | "Low" }) {
  const n = level === "High" ? 3 : level === "Medium" ? 2 : 1;
  return (
    <span className="inline-flex items-center gap-1.5 text-[12px] text-ink-2">
      <span aria-hidden className="flex gap-0.5">
        {[1, 2, 3].map((i) => (
          <span key={i} className={cn("h-2.5 w-1 rounded-full", i <= n ? "bg-ai" : "bg-line-strong")} />
        ))}
      </span>
      {level} confidence
    </span>
  );
}

/** Numbered citation chip linking an AI claim to its evidence. */
export function AiCitation({ n, label, locator }: { n: number; label: string; locator?: string }) {
  return (
    <li className="flex items-start gap-2 text-[12px]">
      <span className="mt-px flex size-4 shrink-0 items-center justify-center rounded-xs bg-ai/10 text-[10px] font-semibold text-ai">{n}</span>
      <span className="text-ink-2">
        {label}
        {locator && <span className="text-ink-4"> · {locator}</span>}
      </span>
    </li>
  );
}
