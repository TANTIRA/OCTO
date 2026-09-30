"use client";

import { animate } from "animejs";
import Image from "next/image";
import core from "@/public/renders/core-octo.webp";
import { revealOnScroll, useAnime } from "./motion";

// Class labels straight from ontology/octo-investment-owl.ttl.
const RINGS = [
  {
    size: 44,
    turn: 60000,
    classes: ["Fund", "Deal", "Investment", "Commitment"],
  },
  {
    size: 70,
    turn: -90000,
    classes: [
      "Limited partner",
      "Fund manager",
      "Operating company",
      "Document",
      "Instrument",
    ],
  },
  {
    size: 96,
    turn: 120000,
    classes: [
      "Ledger event",
      "Valuation event",
      "Extracted claim",
      "Screening decision",
      "Wallet",
      "Sector",
    ],
  },
];

// Rounded so server and client render identical style strings.
const pct = (v: number) => Math.round((50 + 50 * v) * 100) / 100;

const POINTS = [
  "Modeled in OWL, constrained with SHACL",
  "Versioned in Git and reviewed like code",
  "Served from Neo4j beside the PostgreSQL ledger",
];

export function Ontology() {
  const root = useAnime<HTMLElement>((scope, reduce) => {
    if (reduce) return;
    const el = scope.root as HTMLElement;
    revealOnScroll(el);
    el.querySelectorAll<HTMLElement>(".ring").forEach((ring) => {
      const turn = Number(ring.dataset.turn);
      const spin = {
        duration: Math.abs(turn),
        ease: "linear",
        loop: true,
      } as const;
      animate(ring, { rotate: turn > 0 ? 360 : -360, ...spin });
      animate(ring.querySelectorAll(".orbit-label"), {
        rotate: turn > 0 ? -360 : 360,
        ...spin,
      });
    });
    animate(".core", {
      translateY: ["-3%", "3%"],
      duration: 3600,
      ease: "inOutSine",
      alternate: true,
      loop: true,
    });
  });

  return (
    <section
      id="ontology"
      ref={root}
      className="scroll-mt-16 overflow-hidden bg-black px-4 py-24 text-white sm:px-6 sm:py-32 lg:px-8"
    >
      <div className="mx-auto grid max-w-[1320px] items-center gap-16 lg:grid-cols-[0.9fr_1.1fr]">
        <div>
          <p
            data-anim
            className="font-figures text-xs uppercase tracking-[0.18em] text-white/50"
          >
            Ontology
          </p>
          <h2
            data-anim
            className="mt-6 font-display text-4xl font-semibold leading-[1.02] tracking-[-0.035em] sm:text-6xl"
          >
            Our ontology,
            <br />
            your data.
          </h2>
          <p
            data-anim
            className="mt-6 max-w-lg font-editorial text-xl leading-relaxed text-white/65"
          >
            OCTO describes the private-markets world once — parties, funds,
            deals, instruments, and the events between them — and every source
            maps onto it. Screening, analytics, and reporting all read the same
            graph.
          </p>
          <ul className="mt-10 max-w-lg">
            {POINTS.map((p) => (
              <li
                key={p}
                data-anim
                className="flex items-center gap-4 border-t border-white/10 py-4 text-base"
              >
                <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-signal" />
                {p}
              </li>
            ))}
          </ul>
        </div>

        <div
          data-anim
          role="img"
          aria-label={`Ontology classes orbiting the OCTO core: ${RINGS.flatMap((r) => r.classes).join(", ")}.`}
          className="relative mx-auto aspect-square w-full max-w-[600px]"
        >
          {/* Phones drop the outer ring's labels (they collide at this size) and
              inset the rest so every label stays on screen as it orbits. */}
          <div className="absolute inset-[6%] sm:inset-[3%]">
            <div className="absolute inset-[22%] rounded-full bg-[radial-gradient(closest-side,oklch(55%_0.18_256/0.5),transparent)]" />
            <Image
              src={core}
              alt=""
              width={220}
              className="core absolute left-1/2 top-1/2 w-[46%] -translate-x-1/2 -translate-y-1/2"
            />
            {RINGS.map((ring, r) => (
              <div
                key={ring.size}
                data-turn={ring.turn}
                aria-hidden
                className="ring absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-full border border-white/10"
                style={{ width: `${ring.size}%`, height: `${ring.size}%` }}
              >
                {ring.classes.map((c, i) => {
                  const a =
                    (i / ring.classes.length) * 2 * Math.PI - Math.PI / 2;
                  return (
                    <span
                      key={c}
                      className="absolute -translate-x-1/2 -translate-y-1/2"
                      style={{
                        left: `${pct(Math.cos(a))}%`,
                        top: `${pct(Math.sin(a))}%`,
                      }}
                    >
                      <span
                        className={`orbit-label block whitespace-nowrap${r === RINGS.length - 1 ? " max-sm:hidden" : ""} rounded-full border border-white/15 bg-black px-2 py-1 text-[10px] font-medium text-white/80 sm:px-3 sm:py-1.5 sm:text-sm`}
                      >
                        {c}
                      </span>
                    </span>
                  );
                })}
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
