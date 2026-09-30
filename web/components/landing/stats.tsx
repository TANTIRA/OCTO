"use client";

import { animate, onScroll, stagger } from "animejs";
import { revealOnScroll, spring, useAnime } from "./motion";

// Every figure is a design invariant or a count from the repo — not marketing.
const STATS = [
  { value: "1", label: "book of record for funds, deals, portfolio companies, and LPs" },
  { value: "100%", label: "of positions and cash derived from the transaction ledger" },
  { value: "0", label: "in-place edits — corrections supersede, never overwrite" },
  // owl:Class count in ontology/octo-investment-owl.ttl
  { value: "45", label: "ontology classes, OWL + SHACL, versioned in Git" },
];

const DIGITS = Array.from({ length: 20 }, (_, i) => i % 10);
// Column holds 0–9 twice; land on the second pass so every digit rolls.
const restingY = (d: number) => `${-((10 + d) / 20) * 100}%`;

function Odometer({ value }: { value: string }) {
  return (
    <span aria-hidden className="flex leading-none">
      {[...value].map((ch, i) =>
        /\d/.test(ch) ? (
          <span key={i} className="relative inline-block h-[1em] overflow-hidden">
            <span
              className="digit flex flex-col"
              data-digit={ch}
              style={{ transform: `translateY(${restingY(Number(ch))})` }}
            >
              {DIGITS.map((d, j) => (
                <span key={j} className="h-[1em]">
                  {d}
                </span>
              ))}
            </span>
          </span>
        ) : (
          <span key={i}>{ch}</span>
        ),
      )}
    </span>
  );
}

export function Stats() {
  const root = useAnime<HTMLElement>((scope, reduce) => {
    if (reduce) return;
    const el = scope.root as HTMLElement;
    revealOnScroll(el);
    const digits = el.querySelectorAll<HTMLElement>(".digit");
    animate(digits, {
      translateY: (t: unknown) => ["0%", restingY(Number((t as HTMLElement).dataset.digit))],
      delay: stagger(110),
      ease: spring.long(),
      autoplay: onScroll({ target: digits[0], enter: "90% start" }),
    });
  });

  return (
    <section ref={root} className="bg-white px-4 py-24 text-black sm:px-6 sm:py-32 lg:px-8">
      <div className="mx-auto max-w-[1320px]">
        <h2
          data-anim
          className="max-w-3xl font-display text-4xl font-semibold leading-[1.02] tracking-[-0.035em] sm:text-6xl"
        >
          Built as one system, not an integration project.
        </h2>
        <dl className="mt-16 grid gap-x-8 gap-y-12 sm:grid-cols-2 lg:grid-cols-4">
          {STATS.map((s) => (
            <div key={s.label} data-anim className="border-t border-black/15 pt-6">
              <dt className="sr-only">{s.label}</dt>
              <dd className="font-display text-7xl font-semibold tracking-[-0.05em] tabular-nums sm:text-8xl">
                <span className="sr-only">{s.value}</span>
                <Odometer value={s.value} />
              </dd>
              <dd className="mt-4 max-w-[16rem] text-base leading-snug text-neutral-500">
                {s.label}
              </dd>
            </div>
          ))}
        </dl>
      </div>
    </section>
  );
}
