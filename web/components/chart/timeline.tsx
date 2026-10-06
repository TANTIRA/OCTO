import { AlertTriangle, CheckCircle2, Clock3, History, OctagonAlert } from "lucide-react";
import { cn } from "@/lib/utils";

export type ActivityState = "complete" | "historical" | "pending" | "attention" | "critical";
export type ActivityItem = { id: string; at: string; label: string; title: string; state: ActivityState; detail?: string; source?: string; actor?: string; href?: string };

const ACTIVITY_STATE: Record<ActivityState, { icon: React.ReactNode; mark: string; text: string; label: string }> = {
  complete: { icon: <CheckCircle2 />, mark: "text-mark-info bg-mark-info/10", text: "text-info", label: "Complete" },
  historical: { icon: <History />, mark: "text-mark-neutral bg-sunken", text: "text-ink-3", label: "Recorded" },
  pending: { icon: <Clock3 />, mark: "text-mark-neutral bg-surface ring-1 ring-inset ring-line-strong", text: "text-ink-3", label: "Pending" },
  attention: { icon: <AlertTriangle />, mark: "text-mark-warn bg-mark-warn/10", text: "text-warn", label: "Needs attention" },
  critical: { icon: <OctagonAlert />, mark: "text-mark-danger bg-mark-danger/10", text: "text-danger", label: "Critical" },
};

const DAY = 86_400_000;
/** Chronology buckets relative to the demo clock: Today · This week · Earlier. */
function bucket(at: string, now: Date) {
  const age = now.getTime() - new Date(at).getTime();
  return age < DAY && new Date(at).getUTCDate() === now.getUTCDate() ? "Today" : age < 7 * DAY ? "This week" : "Earlier";
}

/**
 * Activity timeline (V2 §29, FUNDS-020…022). Dot, connector, title, detail,
 * actor, source, time and state. State has its own icon, colour and text
 * label — complete blue, historical grey, pending outlined, attention amber,
 * critical red — so it never depends on colour alone. With `now`, entries are
 * grouped into Today / This week / Earlier to read as a chronology.
 */
export function ActivityTimeline({ items, now, className }: { items: ActivityItem[]; now?: Date; className?: string }) {
  const groups = now
    ? items.reduce<{ name: string; items: ActivityItem[] }[]>((acc, e) => {
        const name = bucket(e.at, now);
        const g = acc.find((x) => x.name === name);
        if (g) g.items.push(e);
        else acc.push({ name, items: [e] });
        return acc;
      }, [])
    : [{ name: "", items }];
  return (
    <div className={cn("space-y-4", className)}>
      {groups.map((g) => (
        <section key={g.name || "all"} aria-label={g.name || undefined}>
          {g.name && <h3 className="mb-2 text-label uppercase text-ink-4">{g.name}</h3>}
          <ol className="relative">
            {g.items.map((e, i) => {
              const s = ACTIVITY_STATE[e.state];
              return (
                <li key={e.id} className="relative flex gap-3 pb-4 last:pb-0">
                  {i < g.items.length - 1 && <span aria-hidden className="absolute bottom-0 left-[11px] top-7 w-px bg-line" />}
                  <span aria-hidden className={cn("relative flex size-6 shrink-0 items-center justify-center rounded-full [&>svg]:size-3.5", s.mark)}>
                    {s.icon}
                  </span>
                  <div className="min-w-0 flex-1 pt-0.5">
                    <p className="text-[13px] font-medium leading-snug text-ink">{e.title}</p>
                    {e.detail && <p className="mt-0.5 text-[12px] leading-snug text-ink-2">{e.detail}</p>}
                    <p className="mt-0.5 flex flex-wrap items-center gap-x-1.5 text-[12px]">
                      <span className={cn("font-medium", s.text)}>{s.label}</span>
                      {e.actor && (
                        <>
                          <span aria-hidden className="text-ink-4">·</span>
                          <span className="text-ink-3">{e.actor}</span>
                        </>
                      )}
                      {e.source && (
                        <>
                          <span aria-hidden className="text-ink-4">·</span>
                          <span className="text-ink-3">{e.source}</span>
                        </>
                      )}
                      <span aria-hidden className="text-ink-4">·</span>
                      <time className="text-ink-3" dateTime={e.at}>
                        {e.label}
                      </time>
                    </p>
                  </div>
                </li>
              );
            })}
          </ol>
        </section>
      ))}
    </div>
  );
}
