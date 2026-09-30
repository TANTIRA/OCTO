"use client";

import {
  animate,
  createDrawable,
  createMotionPath,
  createTimeline,
  splitText,
  stagger,
  utils,
} from "animejs";
import { ArrowRight } from "lucide-react";
import { EASE, useAnime } from "./motion";

// Eight governed source families converging on one ledger — the "octo".
const SOURCES = [
  "CRM",
  "Fund admin",
  "Custodians",
  "Market data",
  "Documents",
  "Onchain",
  "Cap tables",
  "Spreadsheets",
];

const C = 320; // viewBox centre
const round = (n: number) => Math.round(n * 10) / 10;
const polar = (r: number, deg: number) => {
  const a = (deg * Math.PI) / 180;
  return [round(C + r * Math.cos(a)), round(C + r * Math.sin(a))] as const;
};

// Each arm curls a quarter-turn on its way in, like a tentacle. Keep these in
// sync with the Blender scene behind /renders/hero-octo.webp (same curves,
// 1 unit = 100px, orthographic top-down) so the overlay rides the tubes.
const ARMS = SOURCES.map((label, i) => {
  const deg = i * 45 - 67.5;
  const [sx, sy] = polar(210, deg);
  const [qx, qy] = polar(150, deg + 38);
  const [ex, ey] = polar(40, deg + 10);
  const [lx, ly] = polar(232, deg);
  const cos = Math.cos((deg * Math.PI) / 180);
  const anchor: "start" | "end" | "middle" = cos > 0.3 ? "start" : cos < -0.3 ? "end" : "middle";
  return { label, d: `M${sx} ${sy} Q${qx} ${qy} ${ex} ${ey}`, lx, ly, anchor };
});

export function Hero() {
  const root = useAnime<HTMLElement>((scope, reduce) => {
    if (reduce) return;
    const el = scope.root as HTMLElement;
    const heading = el.querySelector("h1")!;
    const { words } = splitText(heading, { words: { wrap: "clip" } });
    const arms = createDrawable(".arm");
    utils.set(heading, { opacity: 1 });
    utils.set(arms, { draw: "0 0" });
    utils.set(".source", { opacity: 0 });

    createTimeline({ defaults: { ease: EASE, duration: 1200 } })
      .add(".hero-eyebrow", { opacity: [0, 1], translateY: [12, 0] }, 100)
      .add(words, { translateY: ["110%", "0%"], delay: stagger(60) }, 200)
      .add(".hero-copy", { opacity: [0, 1], translateY: [20, 0], delay: stagger(120) }, 700)
      .add(".hero-graph", { opacity: [0, 1], duration: 600 }, 400)
      .add(".octo-render", { opacity: [0, 1], scale: [0.92, 1], duration: 1800 }, 400)
      .add(arms, { draw: ["0 0", "0 1"], duration: 1600, delay: stagger(90) }, 400)
      .add(".source", { opacity: [0, 1], scale: [0.6, 1], delay: stagger(90) }, 900);

    animate(".core-ring", { rotate: 360, duration: 48000, ease: "linear", loop: true });
    animate(".core-pulse", {
      scale: [1, 1.7],
      opacity: [0.45, 0],
      duration: 2400,
      ease: "outSine",
      loop: true,
    });

    el.querySelectorAll<SVGPathElement>(".arm").forEach((path, i) => {
      animate(el.querySelectorAll(".packet")[i], {
        ...createMotionPath(path),
        opacity: [0, 1, 1, 0],
        duration: 2600,
        delay: 1800 + i * 330,
        loopDelay: 900,
        ease: "inOutSine",
        loop: true,
      });
    });
  });

  return (
    <section
      id="top"
      ref={root}
      className="relative isolate overflow-hidden bg-black text-white"
    >
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(60%_50%_at_75%_45%,oklch(40%_0.14_256/0.45),transparent_70%)]"
      />
      <div className="mx-auto grid min-h-[100svh] max-w-[1320px] items-center gap-6 px-4 pb-20 pt-28 sm:px-6 lg:grid-cols-[1.05fr_1fr] lg:gap-10 lg:px-8 lg:pt-24">
        <div className="relative z-10">
          <a
            href="#contact"
            data-anim
            className="hero-eyebrow inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/5 px-3.5 py-1.5 text-xs font-medium tracking-wide text-white/80 transition-colors hover:bg-white/10"
          >
            <span className="h-1.5 w-1.5 rounded-full bg-signal" />
            Private beta — onboarding now open
          </a>

          <h1
            data-anim
            className="mt-7 font-display text-[clamp(2.9rem,6vw,5.75rem)] font-semibold leading-[0.95] tracking-[-0.045em]"
          >
            One book of record for private markets.
          </h1>

          <p
            data-anim
            className="hero-copy mt-7 max-w-xl font-editorial text-xl leading-relaxed text-white/70 sm:text-2xl"
          >
            OCTO normalizes funds, deals, portfolio companies, and LPs into a
            single governed ledger — with screening, diligence, analytics, and
            LP reporting built on the same numbers.
          </p>

          <div data-anim className="hero-copy mt-10 flex flex-col gap-3 sm:flex-row">
            <a
              href="#contact"
              className="group inline-flex items-center justify-center gap-2 rounded-full bg-white px-7 py-3.5 text-sm font-medium text-black transition-colors hover:bg-neutral-200"
            >
              Request access
              <ArrowRight className="h-4 w-4 transition-transform group-hover:translate-x-0.5" />
            </a>
            <a
              href="#platform"
              className="inline-flex items-center justify-center rounded-full bg-white/10 px-7 py-3.5 text-sm font-medium text-white transition-colors hover:bg-white/15"
            >
              Explore the platform
            </a>
          </div>
        </div>

        <div data-anim className="hero-graph relative mx-auto w-full max-w-[600px]">
          <svg
            viewBox="0 0 640 640"
            role="img"
            aria-label="Eight source systems — CRM, fund admin, custodians, market data, documents, onchain, cap tables, and spreadsheets — converging into one investment book of record."
            className="h-auto w-full"
          >
            <defs>
              <mask id="outside-hub" maskUnits="userSpaceOnUse" x="0" y="0" width="640" height="640">
                <rect width="640" height="640" fill="white" />
                <circle cx={C} cy={C} r="60" fill="black" />
              </mask>
            </defs>
            {/* Cycles render of the octo — see comment on ARMS. */}
            <image
              href="/renders/hero-octo.webp"
              width="640"
              height="640"
              className="octo-render origin-center [transform-box:fill-box]"
            />
            {ARMS.map((a) => (
              <path
                key={a.label}
                className="arm"
                d={a.d}
                fill="none"
                stroke="oklch(72% 0.17 256)"
                strokeOpacity="0.7"
                strokeWidth="1"
                strokeLinecap="round"
                mask="url(#outside-hub)"
              />
            ))}
            {ARMS.map((a) => (
              <g key={a.label} className="source origin-center [transform-box:fill-box]">
                <text
                  x={a.lx}
                  y={a.ly}
                  dy="0.35em"
                  textAnchor={a.anchor}
                  className="fill-white/75 font-display text-[15px] font-medium"
                >
                  {a.label}
                </text>
              </g>
            ))}
            <g mask="url(#outside-hub)">
              {ARMS.map((a) => (
                <circle key={a.label} className="packet fill-signal" r="3.5" opacity="0" />
              ))}
            </g>

            <circle className="core-pulse origin-center [transform-box:fill-box]" cx={C} cy={C} r="62" fill="none" stroke="oklch(64% 0.19 256)" />
            <circle
              className="core-ring origin-center [transform-box:fill-box]"
              cx={C}
              cy={C}
              r="84"
              fill="none"
              stroke="white"
              strokeOpacity="0.2"
              strokeDasharray="2 7"
            />
            <text x={C} y={C - 6} textAnchor="middle" className="fill-black font-display text-[22px] font-semibold tracking-tight">
              IBOR
            </text>
            <text x={C} y={C + 16} textAnchor="middle" className="fill-neutral-500 font-figures text-[10px] uppercase tracking-[0.14em]">
              one ledger
            </text>
          </svg>
        </div>
      </div>

      <a
        href="#platform"
        className="absolute bottom-6 left-1/2 hidden -translate-x-1/2 flex-col items-center gap-2 text-xs font-medium text-white/50 transition-colors hover:text-white sm:flex"
      >
        Scroll to explore
        <span className="block h-8 w-px overflow-hidden bg-white/15">
          <span className="block h-full w-full animate-pulse bg-white/60" />
        </span>
      </a>
    </section>
  );
}
