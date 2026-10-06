import Link from "next/link";
import { ArrowUpRight, Lock } from "lucide-react";
import { cn } from "@/lib/utils";
import { Monogram } from "@/components/ui/badge";
import { ring } from "@/components/ui/button";

/*
 * Object page contract (plan §14, §23). Every major entity — fund, company,
 * investment, deal, report — renders the same header anatomy:
 * identity · status · classification · owner · freshness · permissions · actions.
 */

export function ObjectHeader({
  type,
  name,
  status,
  classification,
  facts,
  freshness,
  permission,
  actions,
  tabs,
}: {
  type: string;
  name: string;
  status?: React.ReactNode;
  classification?: string[];
  facts: { label: string; value: React.ReactNode }[];
  freshness?: React.ReactNode;
  permission?: string;
  actions?: React.ReactNode;
  tabs?: React.ReactNode;
}) {
  return (
    <header className="border-b border-line bg-app">
      <div className="mx-auto w-full max-w-[1600px] px-4 pt-5 sm:px-6 lg:px-7">
        <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
          <div className="flex min-w-0 items-start gap-4">
            <Monogram name={name} size="lg" />
            <div className="min-w-0">
              <p className="text-[12px] font-medium text-ink-3">{type}</p>
              <div className="mt-0.5 flex flex-wrap items-center gap-2">
                <h1 className="text-object font-semibold text-ink">{name}</h1>
                {status}
              </div>
              {classification && classification.length > 0 && (
                <p className="mt-1 flex flex-wrap items-center gap-x-2 text-[13px] text-ink-3">
                  {classification.map((c, i) => (
                    <span key={c} className="flex items-center gap-2">
                      {i > 0 && <span aria-hidden className="size-1 rounded-full bg-ink-4" />}
                      {c}
                    </span>
                  ))}
                </p>
              )}
            </div>
          </div>
          {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
        </div>
        <div className="mt-4 flex flex-wrap items-end gap-x-8 gap-y-2 pb-4 text-[12px]">
          <dl className="contents">
            {facts.map((f) => (
              <div key={f.label}>
                <dt className="text-ink-3">{f.label}</dt>
                <dd className="mt-0.5 font-medium text-ink">{f.value}</dd>
              </div>
            ))}
          </dl>
          {(freshness || permission) && (
            <div className="flex items-end gap-4">
              {freshness}
              {permission && (
                <span className="inline-flex items-center gap-1 text-ink-3">
                  <Lock aria-hidden className="size-3" /> {permission}
                </span>
              )}
            </div>
          )}
        </div>
        {tabs}
      </div>
    </header>
  );
}

/** Two-column definition list for object summaries. */
export function ObjectMetadata({ items, columns = 2, className }: { items: { label: string; value: React.ReactNode }[]; columns?: 1 | 2 | 3; className?: string }) {
  return (
    <dl className={cn("grid gap-x-6 gap-y-3 text-[13px]", columns === 2 && "sm:grid-cols-2", columns === 3 && "sm:grid-cols-3", className)}>
      {items.map((i) => (
        <div key={i.label} className="min-w-0">
          <dt className="text-[12px] text-ink-3">{i.label}</dt>
          <dd className="mt-0.5 truncate text-ink">{i.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Related objects with drill-down links (portfolio → fund → investment → company). */
export function ObjectLinks({ links }: { links: { type: string; name: string; href?: string; meta?: string }[] }) {
  return (
    <ul className="divide-y divide-line">
      {links.map((l) => (
        <li key={`${l.type}-${l.name}`} className="flex items-center gap-3 py-2">
          <Monogram name={l.name} size="sm" />
          <div className="min-w-0 flex-1">
            <p className="text-[11px] font-medium text-ink-4">{l.type}</p>
            {l.href ? (
              <Link href={l.href} className={cn("inline-flex items-center gap-1 truncate rounded-sm text-[13px] font-medium text-ink hover:text-accent", ring)}>
                {l.name} <ArrowUpRight aria-hidden className="size-3" />
              </Link>
            ) : (
              <p className="truncate text-[13px] font-medium text-ink">{l.name}</p>
            )}
          </div>
          {l.meta && <span className="shrink-0 text-[12px] tabular-nums text-ink-3">{l.meta}</span>}
        </li>
      ))}
    </ul>
  );
}
