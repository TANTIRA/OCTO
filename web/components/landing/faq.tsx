import { Plus } from "lucide-react";
import { Reveal } from "./motion";

const FAQ = [
  {
    q: "Who is OCTO for?",
    a: "Private-equity firms and allocators who are tired of rebuilding the same numbers in spreadsheets every quarter. OCTO puts funds, deals, portfolio companies, and LP reporting on one shared record.",
  },
  {
    q: "Where does OCTO run?",
    a: "On your own infrastructure. The ledger (PostgreSQL) and the ontology (Neo4j) are self-hosted, so client, position, and LP records stay inside your walls, and your core data doesn't depend on any outside service. The one outside call is to AI models — and confidential data only goes to ones that keep nothing.",
  },
  {
    q: "If nothing is ever overwritten, how do I fix a mistake?",
    a: "You add a correction. It points to the original entry and must include a reason, so the history stays complete and every reported number can still be traced. Duplicate imports from source systems are rejected automatically.",
  },
  {
    q: "What does the AI actually do?",
    a: "It sorts documents, checks whether claims are backed by their sources, and suggests screening outcomes. Each result records the model and request behind it. Confidential data only goes to AI models that keep nothing (zero data retention), and important actions still need a person to approve them.",
  },
  {
    q: "How do we get access?",
    a: "OCTO is in private beta. Request access below and a specialist will plan a pilot around your fund structure and data sources.",
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
