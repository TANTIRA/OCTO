import { SrTable } from "./core";

/** Stage funnel with conversion between stages (plan §20; deal pipeline). */
export function Funnel({ stages, format, label }: { stages: { label: string; count: number; value?: number }[]; format: (v: number) => string; label: string }) {
  const max = Math.max(...stages.map((s) => s.count), 1);
  return (
    <figure aria-label={label}>
      <ol className="space-y-1.5">
        {stages.map((s, i) => {
          const conv = i > 0 && stages[i - 1].count ? Math.round((s.count / stages[i - 1].count) * 100) : null;
          return (
            <li key={s.label} className="grid grid-cols-[7.5rem_1fr_3rem] items-center gap-3 text-[12px]">
              <span className="truncate font-medium text-ink-2">{s.label}</span>
              <span className="relative h-6 overflow-hidden rounded-md bg-muted">
                <span className="absolute inset-y-0 left-0 rounded-md bg-accent/25" style={{ width: `${Math.max(4, (s.count / max) * 100)}%` }} />
                <span className="relative flex h-full items-center px-2 font-semibold tabular-nums text-ink">
                  {s.count}
                  {s.value !== undefined && <span className="ml-1.5 font-normal text-ink-2">· {format(s.value)}</span>}
                </span>
              </span>
              <span className="text-right tabular-nums text-ink-4">{conv === null ? "" : `${conv}%`}</span>
            </li>
          );
        })}
      </ol>
      <SrTable caption={label} head={["Stage", "Count", "Conversion"]} rows={stages.map((s, i) => [s.label, s.count, i > 0 && stages[i - 1].count ? `${Math.round((s.count / stages[i - 1].count) * 100)}%` : "—"])} />
    </figure>
  );
}
