import { Plus } from "lucide-react";
import { Reveal } from "./motion";

const FAQ = [
  {
    q: "Who is OCTO for?",
    a: "Private-equity managers and allocators who want funds, deals, portfolio companies, and LP reporting on one governed book of record — instead of a stack of spreadsheets reconciled by hand every quarter.",
  },
  {
    q: "Where does OCTO actually run?",
    a: "On your infrastructure. The platform deploys as a self-hosted stack — PostgreSQL for the ledger, Neo4j for the ontology — so client, position, and LP data never leave your perimeter. There is no external SaaS dependency for core data.",
  },
  {
    q: "If the ledger is append-only, how do corrections work?",
    a: "By supersession, never mutation. A correcting event references the original and carries a mandatory rationale, so every figure in a report can be traced back through its full history. Replay protection rejects duplicate writes from source systems.",
  },
  {
    q: "What does the AI actually decide?",
    a: "It classifies documents, assesses claim support, and proposes screening outcomes — every decision carries model, provider, and request lineage. Confidential data is blocked from external APIs at the trust boundary, and high-impact actions still require human approval.",
  },
  {
    q: "How do we get access?",
    a: "OCTO is in private beta. Request access below and a specialist will scope a pilot against your fund structure and data sources.",
  },
];

export function Faq() {
  return (
    <section id="faq" className="scroll-mt-16 bg-neutral-100 px-4 py-24 text-black sm:px-6 sm:py-32 lg:px-8">
      <Reveal className="mx-auto grid max-w-[1320px] gap-12 lg:grid-cols-[0.9fr_1.1fr] lg:gap-20">
        <h2 data-anim className="font-display text-4xl font-semibold leading-[1.02] tracking-[-0.035em] sm:text-6xl">
          Straight answers.
        </h2>
        <div className="border-b border-black/15">
          {FAQ.map((f) => (
            <details key={f.q} name="faq" data-anim className="group border-t border-black/15">
              <summary className="flex cursor-pointer items-center justify-between gap-6 py-6 text-lg font-medium tracking-[-0.01em] focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-black sm:text-xl">
                {f.q}
                <Plus className="h-5 w-5 shrink-0 transition-transform duration-300 group-open:rotate-45" />
              </summary>
              <p className="max-w-2xl pb-7 text-base leading-relaxed text-neutral-600">{f.a}</p>
            </details>
          ))}
        </div>
      </Reveal>
    </section>
  );
}
