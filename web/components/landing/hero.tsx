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
import Image from "next/image";
import field from "@/public/renders/hero-field.webp";
import rig from "./hero-rig.json";
import { spring, useAnime } from "./motion";

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

const WAVE = 3200; // one packet wave per loop — the hero's recurring event
const pct = (v: number) => `${Math.round((v / 640) * 10000) / 100}%`;
const { hub, core, hubImage } = rig;

// Geometry of the Blender scene behind /renders/hero-*.webp: each arm's
// centreline (tip to root), tip and label anchor, projected through its camera
// by build/landing-renders/mech_export.py. Re-render and re-export together.
const ARMS = SOURCES.map((label, i) => {
  const a = rig.arms[i];
  return {
    label,
    d: a.d,
    sx: a.tip[0],
    sy: a.tip[1],
    w: a.w,
    left: pct(a.label[0]),
    top: pct(a.label[1]),
    east: a.east,
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
    const lead = el.querySelector(".hero-lead")!;
    const { words } = splitText(heading, { words: { wrap: "clip" } });
    const { words: leadWords } = splitText(lead, { words: { wrap: "clip" } });
    const reach = createDrawable(".reach");
    const filaments = createDrawable(".arm");

    // Start states go on before anything is revealed, so nothing paints twice.
    utils.set([...words, ...leadWords, ".rise"], { translateY: "110%" });
    // UI rises from further down so it clears the focus-ring padding of its mask.
    utils.set([".hero-eyebrow", ".hero-cta"], { translateY: "160%" });
    utils.set([...reach, ...filaments], { draw: "0 0" });
    utils.set([".bead", ".hub"], { scale: 0 });
    const at = `at ${pct(core.cx)} ${pct(core.cy)}`;
    utils.set(".field", { clipPath: `circle(0% ${at})` });
    utils.set([heading, lead, ".hero-graph", ".hero-clip"], { opacity: 1 });
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

    // H1–H4: headline, sources, reach, arrival. The hub exists only once the
    // tentacles get there — cause before effect.
    createTimeline()
      .add(".hero-eyebrow", { translateY: "0%", ease: spring.snappy() }, 0)
      .add(
        words,
        { translateY: "0%", ease: spring.standard(), delay: stagger(70) },
        60,
      )
      .add(
        ".bead",
        { scale: 1, ease: spring.snappy(), delay: stagger(60) },
        300,
      )
      .add(
        ".source .rise",
        { translateY: "0%", ease: spring.snappy(), delay: stagger(60) },
        340,
      )
      .add(
        leadWords,
        { translateY: "0%", ease: spring.standard(), delay: stagger(12) },
        480,
      )
      .add(reach, { draw: "0 1", ease: spring.long(), delay: stagger(60) }, 560)
      .add(
        filaments,
        { draw: "0 1", ease: spring.long(), delay: stagger(60) },
        560,
      )
      .add(".hero-cta", { translateY: "0%", ease: spring.snappy() }, 820)
      .add(".hub", { scale: 1, ease: spring.playful() }, 1220)
      // The data field lights up outward from the ledger the moment it forms.
      .add(
        ".field",
        { clipPath: `circle(80% ${at})`, ease: spring.long() },
        1240,
      )
      .add(".ibor .rise", { translateY: "0%", ease: spring.snappy() }, 1360);

    // H5: a wave of packets rides the tentacles; the hub absorbs it and ripples.
    // Every element starts and ends the wave invisible or at rest, so the loop
    // has no seam. The bump moves a wrapper, never the hub the intro springs in.
    const packets = el.querySelectorAll(".packet");
    const wave = createTimeline({ loop: true, delay: 2000 });
    el.querySelectorAll<SVGPathElement>(".arm").forEach((path, i) => {
      wave.add(
        packets[i],
        {
          ...createMotionPath(path),
          opacity: [0, 1, 1, 0],
          ease: spring.long(),
        },
        i * 45,
      );
    });
    wave
      .add(".hub-bump", { scale: [1, 1.035], ease: spring.snappy() }, 700)
      .add(".hub-bump", { scale: [1.035, 1], ease: spring.snappy() }, 860)
      .add(
        ".ripple",
        { scale: [1, 1.7], opacity: [0.5, 0], ease: spring.long() },
        720,
      )
      .add(".ripple", { opacity: 0, duration: 1 }, WAVE - 1);

    animate(".scroll-bead", {
      translateY: ["-100%", "300%"],
      ease: spring.long(),
      loop: true,
      loopDelay: 600,
    });
  });

  return (
    <section
      id="top"
      ref={root}
      className="relative isolate overflow-hidden bg-black text-white"
    >
      <div className="mx-auto grid min-h-[100svh] max-w-[1320px] items-center gap-6 px-4 pb-20 pt-28 sm:px-6 lg:grid-cols-[1.05fr_1fr] lg:gap-10 lg:px-8 lg:pt-24">
        <div className="relative z-10">
          <div data-anim className="hero-clip -m-2 overflow-hidden p-2">
            <a
              href="#contact"
              className="hero-eyebrow inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/5 px-3.5 py-1.5 text-xs font-medium tracking-wide text-white/80 transition-colors hover:bg-white/10"
            >
              <span className="h-1.5 w-1.5 rounded-full bg-signal" />
              Private beta — onboarding now open
            </a>
          </div>

          <h1
            data-anim
            className="mt-7 font-display text-[clamp(2.9rem,6vw,5.75rem)] font-semibold leading-[0.95] tracking-[-0.045em]"
          >
            One book of record for private markets.
          </h1>

          <p
            data-anim
            className="hero-lead mt-7 max-w-xl font-editorial text-xl leading-relaxed text-white/70 sm:text-2xl"
          >
            OCTO normalizes funds, deals, portfolio companies, and LPs into a
            single governed ledger — with screening, diligence, analytics, and
            LP reporting built on the same numbers.
          </p>

          <div
            data-anim
            className="hero-clip -mx-2 -mb-2 mt-8 overflow-hidden p-2"
          >
            <div className="hero-cta flex flex-col gap-3 sm:flex-row">
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
        </div>

        <div
          data-anim
          className="hero-graph relative mx-auto w-full max-w-[600px]"
        >
          {/* TouchDesigner data field (streamline trace) as the floor of the
              Blender scene; flattened on black, so it needs no alpha. */}
          <Image
            src={field}
            alt=""
            fill
            priority
            sizes="(min-width: 1024px) 600px, 100vw"
            className="field"
          />
          <svg
            viewBox="0 0 640 640"
            role="img"
            aria-label="Eight source systems — CRM, fund admin, custodians, market data, documents, onchain, cap tables, and spreadsheets — converging into one investment book of record."
            className="relative h-auto w-full"
          >
            <defs>
              {/* Tentacles are revealed by strokes drawn along their own curves. */}
              <mask
                id="hero-reach"
                maskUnits="userSpaceOnUse"
                x="0"
                y="0"
                width="640"
                height="640"
              >
                {ARMS.map((a) => (
                  <path
                    key={a.label}
                    className="reach"
                    d={a.d}
                    fill="none"
                    stroke="white"
                    strokeWidth={a.w}
                  />
                ))}
                {ARMS.map((a) => (
                  <circle
                    key={a.label}
                    className="bead origin-center [transform-box:fill-box]"
                    cx={a.sx}
                    cy={a.sy}
                    r={a.w / 2 + 6}
                    fill="white"
                  />
                ))}
              </mask>
              <mask
                id="hero-outside-hub"
                maskUnits="userSpaceOnUse"
                x="0"
                y="0"
                width="640"
                height="640"
              >
                <rect width="640" height="640" fill="white" />
                <ellipse
                  cx={hub.cx}
                  cy={hub.cy}
                  rx={hub.rx - 2}
                  ry={hub.ry - 2}
                  fill="black"
                />
              </mask>
            </defs>
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

            <image
              href="/renders/hero-arms.webp"
              width="640"
              height="640"
              mask="url(#hero-reach)"
            />
            <g mask="url(#hero-outside-hub)">
              {ARMS.map((a) => (
                <path
                  key={a.label}
                  className="arm"
                  d={a.d}
                  fill="none"
                  stroke="oklch(72% 0.17 256)"
                  strokeOpacity="0.7"
                  strokeWidth="1"
                />
              ))}
              {ARMS.map((a) => (
                <circle
                  key={a.label}
                  className="packet fill-signal"
                  r="3.5"
                  opacity="0"
                />
              ))}
            </g>
            <ellipse
              className="ripple origin-center [transform-box:fill-box]"
              cx={core.cx}
              cy={core.cy}
              rx={core.rx}
              ry={core.ry}
              fill="none"
              stroke="oklch(64% 0.19 256)"
              opacity="0"
            />
            <g className="hub-bump origin-center [transform-box:fill-box]">
              <image
                className="hub origin-center [transform-box:fill-box]"
                href="/renders/hero-hub.webp"
                x={hubImage.x}
                y={hubImage.y}
                width={hubImage.w}
                height={hubImage.h}
              />
            </g>
            <text x={C} y={C - 6} textAnchor="middle" className="fill-black font-display text-[22px] font-semibold tracking-tight">
              IBOR
            </text>
            <text x={C} y={C + 16} textAnchor="middle" className="fill-neutral-500 font-figures text-[10px] uppercase tracking-[0.14em]">
              one ledger
            </text>
          </svg>

          {/* HTML so labels keep a readable size when the graphic scales down. */}
          <div aria-hidden className="pointer-events-none absolute inset-0">
            {ARMS.map((a) => (
              <span
                key={a.label}
                className={`source absolute -translate-y-1/2 overflow-hidden whitespace-nowrap text-xs font-medium text-white/75 sm:text-sm ${a.east ? "" : "-translate-x-full"}`}
                style={{ left: a.left, top: a.top }}
              >
                <span className="rise block">{a.label}</span>
              </span>
            ))}
            <span
              className="ibor absolute -translate-x-1/2 -translate-y-1/2 overflow-hidden text-xs font-semibold tracking-[0.2em] text-white [text-shadow:0_0_12px_rgb(0_0_0)] sm:text-sm"
              style={{ left: pct(core.cx), top: pct(core.cy) }}
            >
              <span className="rise block">IBOR</span>
            </span>
          </div>
        </div>
      </div>

      <a
        href="#platform"
        className="absolute bottom-6 left-1/2 hidden -translate-x-1/2 flex-col items-center gap-2 text-xs font-medium text-white/50 transition-colors hover:text-white sm:flex"
      >
        Scroll to explore
        <span className="block h-8 w-px overflow-hidden bg-white/15">
          <span className="scroll-bead block h-3 w-full bg-white/70" />
        </span>
      </a>
    </section>
  );
}
