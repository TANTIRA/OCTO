"use client";

import { animate, createTimeline, onScroll, splitText, stagger, utils } from "animejs";
import { ArrowRight } from "lucide-react";
import Image from "next/image";
import poster from "@/public/renders/hero-core.webp";
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
});

export function Hero() {
  const root = useAnime<HTMLElement>((scope, reduce) => {
    if (reduce) return;
    const el = scope.root as HTMLElement;
    const heading = el.querySelector("h1")!;
    const { words } = splitText(heading, { words: { wrap: "clip" } });

    // Start states go on before anything is revealed, so nothing paints twice.
    utils.set(words, { translateY: "110%" });
    // UI rises from further down so it clears the focus-ring padding of its mask.
    utils.set([".hero-eyebrow", ".hero-cta"], { translateY: "160%" });
    utils.set(".octo-poster", { clipPath: "circle(0% at 50% 50%)" });
    utils.set([heading, ".hero-graph", ".hero-clip"], { opacity: 1 });
    utils.set([...reach, ...filaments], { draw: "0 0" });
    utils.set([".bead", ".hub"], { scale: 0 });
    const at = `at ${pct(core.cx)} ${pct(core.cy)}`;
    utils.set(".field", { clipPath: `circle(0% ${at})` });
    utils.set([heading, lead, ".hero-graph", ".hero-clip"], { opacity: 1 });

    // Headline first, then the ledger core opens out of the dark under it; the
    // live 3D core (octo-core.tsx) takes over from this poster once it has
    // drawn a frame.
    createTimeline()
      .add(".hero-eyebrow", { translateY: "0%", ease: spring.snappy() }, 0)
      .add(
        words,
        { translateY: "0%", ease: spring.standard(), delay: stagger(70) },
        60,
      )
      .add(".hero-cta", { translateY: "0%", ease: spring.snappy() }, 700)
      .add(
        ".octo-poster",
        { clipPath: "circle(75% at 50% 50%)", ease: spring.long() },
        760,
      )
      // the reveal has covered the dock: the live core may take over now
      .call(() => {
        el.querySelector<HTMLElement>("[data-octo-dock]")!.dataset.intro = "done";
      }, 1500);

    // As the page moves on, the headline blurs, fades and lifts away, and the
    // octo crawls up out of its dock.
    animate(".hero-copy", {
      opacity: [1, 0],
      filter: ["blur(0px)", "blur(10px)"],
      translateY: ["0vh", "-10vh"],
      ease: "linear",
      autoplay: onScroll({
        target: el,
        enter: { target: "top", container: "top" },
        leave: { target: "center", container: "top" },
        sync: true,
      }),
    });
  });

  return (
    <section
      id="top"
      ref={root}
      className="relative overflow-hidden bg-black text-white [--octo:min(680px,88vw,64svh)]"
    >
      {/* Centred, alone on black (a Palantir-style opening); the bottom
          padding keeps the copy clear of the octo's reach below it. */}
      <div className="hero-copy relative z-40 mx-auto flex min-h-[100svh] max-w-[1320px] flex-col items-center justify-center px-4 pb-[calc(var(--octo)*0.47)] pt-28 text-center sm:px-6 lg:px-8">
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
          className="mt-7 max-w-[15ch] font-display text-[clamp(2.9rem,6.4vw,6.25rem)] font-semibold leading-[0.95] tracking-[-0.045em]"
        >
          One book of record for private markets.
        </h1>
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
          </svg>

        <div
          data-anim
          className="hero-clip -mx-2 -mb-2 mt-10 overflow-hidden p-2"
        >
          <div className="hero-cta flex flex-col justify-center gap-3 sm:flex-row">
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

      {/* The octo docks on the hero's bottom edge, half above the fold, and
          crawls out with the scroll. A Cycles poster of the same pose paints
          first; the live 3D core replaces it once it has drawn a frame. */}
      <div
        data-anim
        data-octo-dock
        role="img"
        aria-label="The OCTO ledger core: eight arms bring CRM, fund admin, custodians, market data, documents, onchain, cap tables, and spreadsheets into one investment book of record."
        className="hero-graph group absolute bottom-0 left-1/2 z-0 aspect-square w-[var(--octo)] -translate-x-1/2 translate-y-1/2"
      >
        <Image
          src={poster}
          alt=""
          fill
          priority
          sizes="(min-width: 768px) 680px, 88vw"
          className="octo-poster object-contain transition-opacity duration-700 group-data-[live]:opacity-0"
        />
      </div>
    </section>
  );
}
