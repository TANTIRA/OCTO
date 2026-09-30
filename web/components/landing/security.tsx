import { Plus } from "lucide-react";
import { Reveal } from "./motion";

const PRINCIPLES = [
  {
    title: "Derived, never written",
    body: "Positions and cash are derived from the transaction ledger. Nothing writes position state directly, so every figure traces back to the event that produced it.",
  },
  {
    title: "Fail-closed tenant isolation",
    body: "Tenant-scoped data is guarded by PostgreSQL row-level security that fails closed, with authorization enforced again at the API on every request.",
  },
  {
    title: "Attributed decisions",
    body: "Approvals and screening outcomes record who decided from the authenticated session — never from what the client sends — with model lineage on every AI proposal.",
  },
  {
    title: "Verified sources",
    body: "Source writes are idempotent and replay-safe, and onchain deliveries are checked against the chain's own transaction before they reach the ledger.",
  },
  {
    title: "Inside your perimeter",
    body: "Self-hosted on PostgreSQL and Neo4j. The platform refuses to boot with half-configured identity, and confidential data never reaches external model APIs.",
  },
];

export function Security() {
  return (
    <section id="security" className="scroll-mt-16 bg-white px-4 py-24 text-black sm:px-6 sm:py-32 lg:px-8">
      <Reveal className="mx-auto grid max-w-[1320px] gap-12 lg:grid-cols-[0.9fr_1.1fr] lg:gap-20">
        <div className="lg:sticky lg:top-28 lg:self-start">
          <p data-anim className="font-figures text-xs uppercase tracking-[0.18em] text-neutral-500">
            Trust & controls
          </p>
          <h2 data-anim className="mt-6 font-display text-4xl font-semibold leading-[1.02] tracking-[-0.035em] sm:text-6xl">
            Institutional grade in all we do.
          </h2>
          <p data-anim className="mt-6 max-w-md font-editorial text-xl leading-relaxed text-neutral-600">
            Controls live in the data model and the runtime, not in a policy PDF.
          </p>
        </div>

        <div className="border-b border-black/15">
          {PRINCIPLES.map((p, i) => (
            <details key={p.title} name="security" open={i === 0} data-anim className="group border-t border-black/15">
              <summary className="flex cursor-pointer items-center gap-6 py-7 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-black">
                <span className="font-figures text-sm text-neutral-400">0{i + 1}</span>
                <span className="flex-1 font-display text-2xl font-medium tracking-[-0.02em] sm:text-3xl">
                  {p.title}
                </span>
                <Plus className="h-5 w-5 shrink-0 transition-transform duration-300 group-open:rotate-45" />
              </summary>
              <p className="max-w-xl pb-8 pl-11 text-base leading-relaxed text-neutral-600 sm:text-lg">
                {p.body}
              </p>
            </details>
          ))}
        </div>
      </Reveal>
    </section>
  );
}
