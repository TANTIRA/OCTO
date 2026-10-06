import { AlertTriangle, CheckCircle2, Info, Lock, OctagonAlert } from "lucide-react";
import { cn } from "@/lib/utils";

type Tone = "info" | "warn" | "danger" | "ok" | "demo" | "restricted";

const TONE: Record<Tone, { cls: string; icon: React.ReactNode }> = {
  info: { cls: "border-info/20 bg-info/6 [&>svg]:text-info", icon: <Info /> },
  demo: { cls: "border-info/20 bg-info/6 [&>svg]:text-info", icon: <Info /> },
  warn: { cls: "border-warn/25 bg-warn/8 [&>svg]:text-warn", icon: <AlertTriangle /> },
  danger: { cls: "border-danger/20 bg-danger/6 [&>svg]:text-danger", icon: <OctagonAlert /> },
  ok: { cls: "border-ok/20 bg-ok/6 [&>svg]:text-ok", icon: <CheckCircle2 /> },
  restricted: { cls: "border-line bg-subtle [&>svg]:text-ink-3", icon: <Lock /> },
};

/** Inline, non-blocking message inside a page or panel. */
export function InlineAlert({ tone = "info", title, children, action, className }: { tone?: Tone; title?: string; children: React.ReactNode; action?: React.ReactNode; className?: string }) {
  const t = TONE[tone];
  return (
    <div
      role={tone === "danger" ? "alert" : "note"}
      className={cn("flex items-start gap-2.5 rounded-lg border px-3 py-2.5 text-[12px] leading-relaxed text-ink-2 [&>svg]:mt-px [&>svg]:size-3.5 [&>svg]:shrink-0", t.cls, className)}
    >
      {t.icon}
      <div className="min-w-0 flex-1">
        {title && <p className="font-medium text-ink">{title}</p>}
        <div>{children}</div>
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}
