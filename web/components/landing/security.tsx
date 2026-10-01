import { Plus } from "lucide-react";
import { Reveal } from "./motion";

const PRINCIPLES = [
  {
    title: "Every number traces back",
    body: "Positions and cash are calculated from the transaction history, never typed in — so any figure can be followed back to the event that produced it.",
  },
  {
    title: "Your data stays yours",
    body: "Each firm's data is walled off inside the database itself, and every request is checked again before anything comes back. If a check can't be made, the answer is no.",
  },
  {
    title: "Every decision has a name on it",
    body: "Approvals and screening calls are recorded against the person actually signed in — never a name typed into a form — and every AI suggestion shows which model made it.",
  },
  {
    title: "Clean data in",
    body: "Imports can be re-run safely without creating duplicates, and on-chain transfers are checked against the blockchain itself before they're recorded.",
  },
  {
    title: "Runs on your infrastructure",
    body: "OCTO is self-hosted, so your ledger and ontology live on your servers. It won't start with half-finished sign-in settings, and confidential data only goes to AI models that keep nothing.",
  },
];

export function Security() {
  return (
    <section id="security" className="scroll-mt-16 bg-white px-4 py-24 text-black sm:px-6 sm:py-32 lg:px-8">
      <Reveal className="relative z-40 mx-auto grid max-w-[1320px] gap-12 lg:grid-cols-[0.9fr_1.1fr] lg:gap-20">
        <div className="lg:sticky lg:top-28 lg:self-start">
          <p data-anim className="font-figures text-xs uppercase tracking-[0.18em] text-neutral-500">
            Trust & controls
          </p>
          <h2 data-anim className="mt-6 font-display text-4xl font-semibold leading-[1.02] tracking-[-0.035em] sm:text-6xl">
            Controls you can check, not just trust.
          </h2>
          <p data-anim className="mt-6 max-w-md font-editorial text-xl leading-relaxed text-neutral-600">
            Safeguards are built into the data and the software itself — not left to a policy document.
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
