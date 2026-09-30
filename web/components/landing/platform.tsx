import { ArrowUpRight } from "lucide-react";
import { Reveal } from "./motion";

const PRODUCTS = [
  {
    name: "Investment Book of Record",
    body: "Every commitment, transaction, cash flow, and valuation in one ledger. Positions and cash are calculated from it — nobody types them in by hand.",
    specs: [
      ["History", "Nothing overwritten"],
      ["Corrections", "Logged with a reason"],
      ["Imports", "Never double-counted"],
    ],
  },
  {
    name: "Look-through & Analytics",
    body: "See exposure and performance through every fund and holding layer — numbers you can stand behind in front of an LP.",
    specs: [
      ["Returns", "IRR · TVPI · MOIC · DPI"],
      ["Attribution", "What drove returns"],
      ["Exposure", "Through every layer"],
    ],
  },
  {
    name: "AI you can audit",
    body: "AI sorts documents, checks claims against their sources, and drafts screening calls — and every answer records the model and request behind it.",
    specs: [
      ["Every answer", "Traced to its model"],
      ["Big decisions", "Need a human sign-off"],
      ["Confidential data", "Zero-retention models only"],
    ],
  },
  {
    name: "From first look to LP report",
    body: "Screen deals your way, run diligence, and produce IC and LP reports — all from the same numbers, every time.",
    specs: [
      ["Screening", "Your criteria"],
      ["Approvals", "Built into workflows"],
      ["Reports", "Straight from the ledger"],
    ],
  },
];

export function Platform() {
  return (
    <section id="platform" className="scroll-mt-16 bg-white px-4 pb-24 text-black sm:px-6 sm:pb-32 lg:px-8">
      <Reveal className="mx-auto max-w-[1320px]">
        <div className="grid gap-6 border-t border-black/15 pt-16 lg:grid-cols-2 lg:items-end">
          <h2 data-anim className="font-display text-4xl font-semibold leading-[1.02] tracking-[-0.035em] sm:text-6xl">
            One record for every number your firm reports.
          </h2>
          <p data-anim className="max-w-lg font-editorial text-xl leading-relaxed text-neutral-600 lg:justify-self-end">
            Four tools built on one shared record — so your deal team, back
            office, and LPs always see the same figure, without the quarterly
            reconciliation.
          </p>
        </div>

        <div className="mt-14 grid gap-4 md:grid-cols-2">
          {PRODUCTS.map((p, i) => (
            <article
              key={p.name}
              data-anim
              className="group flex flex-col rounded-[28px] bg-neutral-950 p-7 text-white sm:p-10"
            >
              <span className="font-figures text-xs text-white/40">0{i + 1}</span>
              <h3 className="mt-10 font-display text-3xl font-semibold tracking-[-0.03em] sm:text-4xl">
                {p.name}
              </h3>
              <p className="mt-4 max-w-md text-base leading-relaxed text-white/60">{p.body}</p>
              <dl className="mt-auto grid gap-px pt-12 sm:grid-cols-3">
                {p.specs.map(([k, v]) => (
                  <div key={k} className="border-t border-white/10 pt-4 sm:pr-4">
                    <dt className="text-xs text-white/40">{k}</dt>
                    <dd className="mt-1.5 text-sm font-medium">{v}</dd>
                  </div>
                ))}
              </dl>
              <a
                href="#contact"
                className="mt-8 inline-flex w-fit items-center gap-1.5 rounded-full bg-white/10 px-4 py-2 text-sm font-medium transition-colors hover:bg-white hover:text-black"
              >
                Talk to a specialist
                <ArrowUpRight className="h-4 w-4" />
              </a>
            </article>
          ))}
        </div>
      </Reveal>
    </section>
  );
}
