"use client";

import { createDrawable, createMotionPath, createTimeline, onScroll, stagger, utils } from "animejs";
import {
  Blocks,
  ChartCandlestick,
  ChartLine,
  Contact,
  Database,
  FileChartColumn,
  FileText,
  Landmark,
  ListFilter,
  Presentation,
  SearchCheck,
  Users,
  Vault,
  type LucideIcon,
} from "lucide-react";
import { Reveal, spring, useAnime } from "./motion";

// The ontology as a system: sources plug in, one model of linked objects sits in
// the middle, decisions plug out — and a question travels through it.

const SOURCES: [string, LucideIcon][] = [
  ["CRM", Contact],
  ["Fund admin", Landmark],
  ["Custodians", Vault],
  ["Market data", ChartCandlestick],
  ["Documents", FileText],
  ["Onchain", Blocks],
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
const DECISIONS: [string, LucideIcon][] = [
  ["Screening", ListFilter],
  ["Diligence", SearchCheck],
  ["Analytics", ChartLine],
  ["IC memo", Presentation],
  ["LP reports", FileChartColumn],
];

// Classes and their SHACL-bound properties, from ontology/octo-investment-*.ttl.
const OBJECTS = {
  manager: { title: "Fund manager", rows: ["legalName", "website", "externalId"] },
  fund: { title: "Fund", rows: ["vintageYear", "fundStatus", "currencyCode"] },
  lp: { title: "Limited partner", rows: ["legalName", "lei", "website"] },
  investment: { title: "Investment", rows: ["displayName", "investmentStatus"] },
  deal: { title: "Deal", rows: ["dealSource", "dealStatus", "effectiveDate"] },
  company: { title: "Operating company", rows: ["legalName", "foundedDate", "website"] },
};
type ObjectId = keyof typeof OBJECTS;

// Reified relationships: FundManagement, Commitment, FundInvestment, DealSubject.
const LINKS: [ObjectId, ObjectId, string][] = [
  ["manager", "fund", "manages"],
  ["lp", "fund", "commits"],
  ["fund", "deal", "invests via"],
  ["deal", "investment", "position"],
  ["deal", "company", "subject"],
];

type Query = { from: number; chain: ObjectId[]; to: number };
type Spec = {
  key: string;
  width: number;
  height: number;
  margin: number;
  pad: number;
  gap: number;
  font: number; // labels, rows and link names
  rowH: number;
  sources: { y: number; cols: number; tile: [number, number] };
  decisions: { y: number; cols: number; tile: [number, number]; show: number[] };
  bar: { y: number; h: number; meta: boolean };
  cardW: number;
  objects: Partial<Record<ObjectId, [number, number]>>;
  queries: Query[];
};

const DESKTOP: Spec = {
  key: "d",
  width: 600,
  height: 712,
  margin: 20,
  pad: 14,
  gap: 8,
  font: 12,
  rowH: 20,
  sources: { y: 20, cols: 6, tile: [76, 62] },
  bar: { y: 196, h: 48, meta: true },
  cardW: 149,
  objects: {
    manager: [20, 268],
    fund: [225.5, 268],
    lp: [431, 268],
    investment: [20, 420],
    deal: [225.5, 420],
    company: [431, 420],
  },
  decisions: { y: 572, cols: 5, tile: [92, 62], show: [0, 1, 2, 3, 4] },
  queries: [
    { from: 1, chain: ["lp", "fund", "deal", "company"], to: 4 },
    { from: 0, chain: ["manager", "fund", "deal"], to: 0 },
    { from: 3, chain: ["fund", "deal", "investment"], to: 2 },
  ],
};

// Phones get their own composition — a vertical chain — not a scaled copy.
const PHONE: Spec = {
  key: "p",
  width: 343,
  height: 974,
  margin: 12,
  pad: 12,
  gap: 8,
  font: 13,
  rowH: 21,
  sources: { y: 12, cols: 3, tile: [93, 58] },
  bar: { y: 234, h: 44, meta: false },
  cardW: 259,
  objects: { lp: [42, 302], fund: [42, 437], deal: [42, 572], company: [42, 707] },
  decisions: { y: 850, cols: 3, tile: [93, 58], show: [0, 2, 4] },
  queries: [
    { from: 1, chain: ["lp", "fund", "deal", "company"], to: 4 },
    { from: 0, chain: ["fund", "deal"], to: 0 },
    { from: 3, chain: ["deal", "company"], to: 2 },
  ],
};

const HEADER = 30;
const QUERY = 4000; // one question per 4 s — the section's recurring event
const r1 = (n: number) => Math.round(n * 10) / 10;
const curve = (x0: number, y0: number, x1: number, y1: number) => {
  const k = (y1 - y0) * 0.55;
  return `M${r1(x0)} ${y0} C${r1(x0)} ${r1(y0 + k)} ${r1(x1)} ${r1(y1 - k)} ${r1(x1)} ${y1}`;
};
const spread = (count: number, center: number, step: number) =>
  Array.from({ length: count }, (_, k) => center + (k - (count - 1) / 2) * step);

function panel(s: Spec, y: number, items: [string, LucideIcon][], cols: number, [tw, th]: [number, number]) {
  const w = s.width - 2 * s.margin;
  const rows = Math.ceil(items.length / cols);
  const colGap = (w - 2 * s.pad - cols * tw) / (cols - 1);
  const tiles = items.map(([label, Icon], i) => ({
    label,
    Icon,
    x: r1(s.margin + s.pad + (i % cols) * (tw + colGap)),
    y: y + HEADER + s.pad + Math.floor(i / cols) * (th + s.gap),
    w: tw,
    h: th,
  }));
  return { x: s.margin, y, w, h: HEADER + 2 * s.pad + rows * th + (rows - 1) * s.gap, tiles };
}

function build(s: Spec) {
  const cx = s.width / 2;
  const sources = panel(s, s.sources.y, SOURCES, s.sources.cols, s.sources.tile);
  const shown = s.decisions.show.map((i) => DECISIONS[i]);
  const decisions = panel(s, s.decisions.y, shown, s.decisions.cols, s.decisions.tile);
  const cards = (Object.entries(s.objects) as [ObjectId, [number, number]][]).map(([id, [x, y]]) => {
    const o = OBJECTS[id];
    return { id, ...o, x, y, w: s.cardW, h: 26 + o.rows.length * s.rowH + 10 };
  });
  const card = (id: ObjectId) => cards.find((c) => c.id === id)!;
  const has = (id: ObjectId) => cards.some((c) => c.id === id);

  const links = LINKS.filter(([a, b]) => has(a) && has(b)).map(([a, b, label]) => {
    const A = card(a);
    const B = card(b);
    if (A.y === B.y) {
      const y = A.y + 38;
      const east = B.x > A.x;
      const x1 = east ? A.x + A.w : A.x;
      const x2 = east ? B.x : B.x + B.w;
      const t = east ? -6 : 6;
      return { a, b, label, x1, y1: y, x2, y2: y, lx: (x1 + x2) / 2, ly: y - 7, anchor: "middle" as const, arrow: `M${x2 + t} ${y - 4} L${x2} ${y} L${x2 + t} ${y + 4}` };
    }
    const x = A.x + A.w / 2;
    const y1 = A.y + A.h;
    return { a, b, label, x1: x, y1, x2: x, y2: B.y, lx: x + 8, ly: (y1 + B.y) / 2 + 4, anchor: "start" as const, arrow: `M${x - 4} ${B.y - 6} L${x} ${B.y} L${x + 4} ${B.y - 6}` };
  });

  // Cables: one per source column into the ontology; one per decision out of it.
  const columns = sources.tiles.slice(0, s.sources.cols);
  const inX = spread(columns.length, cx, s.key === "d" ? 20 : 16);
  const cablesIn = columns.map((t, k) => curve(t.x + t.w / 2, sources.y + sources.h, inX[k], s.bar.y));
  const bottom = Math.max(...cards.map((c) => c.y + c.h));
  const outX = spread(decisions.tiles.length, cx, s.key === "d" ? 20 : 16);
  const cablesOut = decisions.tiles.map((t, k) => curve(outX[k], bottom, t.x + t.w / 2, decisions.y));
  const top = Math.min(...cards.map((c) => c.y));
  const dashes = cards.filter((c) => c.y === top).map((c) => `M${c.x + c.w / 2} ${s.bar.y + s.bar.h} V${c.y}`);

  // Each hop of a query runs card centre → link → next card centre. The packet
  // is masked inside cards, so it appears to leave one object and enter the next.
  const hops = s.queries.flatMap((q, n) =>
    q.chain.slice(1).map((id, k) => {
      const A = card(q.chain[k]);
      const B = card(id);
      const l = links.find((x) => x.a === A.id && x.b === B.id)!;
      return { key: `${n}-${k}`, d: `M${A.x + A.w / 2} ${A.y + A.h / 2} L${l.x1} ${l.y1} L${l.x2} ${l.y2} L${B.x + B.w / 2} ${B.y + B.h / 2}` };
    }),
  );
  return { cx, sources, decisions, cards, links, cablesIn, cablesOut, dashes, hops };
}

const INK = "#171717";
const SHEET = "#e8e8e8";
const SIGNAL = "oklch(64% 0.19 256)";
const TINT = "oklch(92% 0.04 256)";
const REVEALED = "inset(0% 0% 0% 0%)";

function Panel({ p, title, Icon, kind, font }: { p: ReturnType<typeof panel>; title: string; Icon: LucideIcon; kind: "src" | "dst"; font: number }) {
  return (
    <g className={`plot ${kind}`}>
      <rect x={p.x} y={p.y} width={p.w} height={p.h} rx="6" fill="#f4f4f4" stroke={INK} />
      <path d={`M${p.x + 6} ${p.y} H${p.x + p.w - 6} A6 6 0 0 1 ${p.x + p.w} ${p.y + 6} V${p.y + HEADER} H${p.x} V${p.y + 6} A6 6 0 0 1 ${p.x + 6} ${p.y}Z`} fill={TINT} stroke={INK} />
      <Icon x={p.x + 12} y={p.y + 8} width={14} height={14} strokeWidth={1.5} color={INK} />
      <text x={p.x + 34} y={p.y + 19.5} fontSize="12" letterSpacing="0.08em" className="font-display font-semibold" fill={INK}>
        {title}
      </text>
      {p.tiles.map((t, i) => (
        <g key={t.label}>
          <g className={`tile ${kind} origin-center [transform-box:fill-box]`}>
            <rect x={t.x} y={t.y} width={t.w} height={t.h} rx="4" fill="#fff" stroke={INK} />
            <t.Icon x={t.x + t.w / 2 - 11} y={t.y + 9} width={22} height={22} strokeWidth={1.25} color={INK} />
            <text x={t.x + t.w / 2} y={t.y + t.h - 11} fontSize={font} textAnchor="middle" className="font-display" fill={INK}>
              {t.label}
            </text>
          </g>
          <rect className={`hl-${kind}`} data-i={i} x={t.x - 2} y={t.y - 2} width={t.w + 4} height={t.h + 4} rx="5" fill="oklch(64% 0.19 256 / 0.1)" stroke={SIGNAL} strokeWidth="1.5" opacity="0" />
        </g>
      ))}
    </g>
  );
}

function Schematic({ spec, className }: { spec: Spec; className: string }) {
  const g = build(spec);
  const f = spec.font;

  const root = useAnime<HTMLDivElement>(
    (scope, reduce) => {
      const el = scope.root as HTMLDivElement;
      if (reduce || el.offsetWidth === 0) return; // the other breakpoint's copy
      const $ = (s: string) => [...el.querySelectorAll(s)];
      const cablesIn = createDrawable($(".cable-in"));
      const cablesOut = createDrawable($(".cable-out"));
      const links = createDrawable($(".link"));
      const pulsesIn = createDrawable($(".pulse-in"));
      const pulsesOut = createDrawable($(".pulse-out"));
      const packet = el.querySelector(".packet")!;

      utils.set($(".plot"), { clipPath: "inset(0% 0% 100% 0%)" });
      utils.set([...cablesIn, ...cablesOut, ...links], { draw: "0 0" });
      utils.set([...pulsesIn, ...pulsesOut], { draw: "0 0", opacity: 1 });
      utils.set($(".tile, .arrow"), { scale: 0 });
      utils.set($(".bar"), { scaleX: 0 });
      utils.set(el, { opacity: 1 });

      // Loop: a question leaves a source, crosses the model object by object
      // along real relations, and lands in a decision. Everything is off again
      // at the end of each question, so the loop has no seam.
      const loop = createTimeline({ autoplay: false, loop: true });
      let hop = 0;
      spec.queries.forEach((q, n) => {
        const t0 = n * QUERY;
        const src = q.from % spec.sources.cols;
        const dst = spec.decisions.show.indexOf(q.to);
        const lit = [...q.chain.map((id) => `.hl-card[data-id="${id}"]`), `.hl-src[data-i="${q.from}"]`, `.hl-dst[data-i="${dst}"]`];
        // Budget (settle times: snappy 0.88 s, standard 0.96 s): the longest
        // chain's last tween ends at t0 + 3.91 s, inside the 4 s slot.
        loop
          .add(`.hl-src[data-i="${q.from}"]`, { opacity: 1, ease: spring.snappy() }, t0)
          .add(pulsesIn[src], { draw: ["0 0", "0 0.3", "0.7 1", "1 1"], ease: spring.standard() }, t0 + 100)
          .add(".hl-bar", { opacity: [0, 1, 0], ease: spring.standard() }, t0 + 600)
          .add(`.hl-card[data-id="${q.chain[0]}"]`, { opacity: 1, ease: spring.snappy() }, t0 + 700)
          .add(packet, { opacity: 1, duration: 1 }, t0 + 700);
        q.chain.slice(1).forEach((id, k) => {
          const at = t0 + 700 + k * 460;
          loop
            .add(packet, { ...createMotionPath($(".hop")[hop++]), ease: spring.standard() }, at)
            .add(`.hl-card[data-id="${id}"]`, { opacity: 1, ease: spring.snappy() }, at + 400);
        });
        const end = t0 + 700 + (q.chain.length - 1) * 460 + 150;
        loop
          .add(packet, { opacity: 0, duration: 1 }, end)
          .add(pulsesOut[dst], { draw: ["0 0", "0 0.3", "0.7 1", "1 1"], ease: spring.standard() }, end + 50)
          .add(`.hl-dst[data-i="${dst}"]`, { opacity: 1, ease: spring.snappy() }, end + 450)
          .add(lit.join(", "), { opacity: 0, ease: spring.standard() }, t0 + 2950);
      });
      loop.add(packet, { opacity: 0, duration: 1 }, spec.queries.length * QUERY - 1); // pins the period

      // Intro, once, on scroll-in: sources plug in, the ontology arrives when
      // their cables reach it, the model is plotted, decisions plug out.
      createTimeline({
        autoplay: onScroll({ target: el, enter: "85% start", repeat: false }),
        onComplete: () => loop.play(),
      })
        .add(".plot.sheet", { clipPath: REVEALED, ease: spring.long() }, 0)
        .add(".plot.src", { clipPath: REVEALED, ease: spring.standard() }, 0)
        .add(".tile.src", { scale: 1, ease: spring.snappy(), delay: stagger(50) }, 150)
        .add(cablesIn, { draw: "0 1", ease: spring.long(), delay: stagger(40) }, 450)
        .add(".bar", { scaleX: 1, ease: spring.playful() }, 1000)
        .add(".plot.bar-text", { clipPath: REVEALED, ease: spring.standard() }, 1150)
        .add(".plot.dashes", { clipPath: REVEALED, ease: spring.standard() }, 1200)
        .add(".plot.card", { clipPath: REVEALED, ease: spring.standard(), delay: stagger(90) }, 1300)
        .add(links, { draw: "0 1", ease: spring.standard(), delay: stagger(90) }, 1650)
        .add(".plot.label", { clipPath: REVEALED, ease: spring.standard(), delay: stagger(90) }, 1750)
        .add(".arrow", { scale: 1, ease: spring.snappy(), delay: stagger(90) }, 1900)
        .add(cablesOut, { draw: "0 1", ease: spring.long(), delay: stagger(40) }, 2000)
        .add(".plot.dst", { clipPath: REVEALED, ease: spring.standard() }, 2350)
        .add(".tile.dst", { scale: 1, ease: spring.snappy(), delay: stagger(50) }, 2500);
    },
    { sm: "(min-width: 640px)" },
  );

  return (
    <div
      ref={root}
      data-anim
      role="img"
      aria-label="OCTO's ontology as a system: CRM, fund admin, custodians, market data, documents, and onchain sources map into one ontology of linked objects — a fund manager manages a fund, a limited partner commits to it, the fund invests via a deal whose subject is an operating company — and screening, diligence, analytics, IC memos, and LP reports all read from it."
      className={className}
    >
      <svg viewBox={`0 0 ${spec.width} ${spec.height}`} className="h-auto w-full" aria-hidden>
        <defs>
          <mask id={`${spec.key}-packet`} maskUnits="userSpaceOnUse" x="0" y="0" width={spec.width} height={spec.height}>
            <rect width={spec.width} height={spec.height} fill="white" />
            {g.cards.map((c) => (
              <rect key={c.id} x={c.x} y={c.y} width={c.w} height={c.h} fill="black" />
            ))}
          </mask>
        </defs>
        <rect className="plot sheet" width={spec.width} height={spec.height} rx="20" fill={SHEET} />

        <g fill="none" stroke="#8a8a8a">
          {g.cablesIn.map((d) => (
            <path key={d} className="cable-in" d={d} />
          ))}
          {g.cablesOut.map((d) => (
            <path key={d} className="cable-out" d={d} />
          ))}
        </g>
        <g className="plot dashes" fill="none" stroke="#9a9a9a" strokeDasharray="3 4">
          {g.dashes.map((d) => (
            <path key={d} d={d} />
          ))}
        </g>

        <Panel p={g.sources} title="SOURCES" Icon={Database} kind="src" font={f} />
        <Panel p={g.decisions} title="DECISIONS" Icon={Users} kind="dst" font={f} />

        <rect className="bar origin-center [transform-box:fill-box]" x={spec.margin} y={spec.bar.y} width={spec.width - 2 * spec.margin} height={spec.bar.h} rx="10" fill="#0a0a0a" />
        <rect className="hl-bar" x={spec.margin - 3} y={spec.bar.y - 3} width={spec.width - 2 * spec.margin + 6} height={spec.bar.h + 6} rx="12" fill="none" stroke={SIGNAL} strokeWidth="2" opacity="0" />
        <g className="plot bar-text">
          <text x={g.cx} y={spec.bar.y + spec.bar.h / 2 + 8} fontSize={spec.bar.meta ? 24 : 22} textAnchor="middle" className="font-display font-medium" fill="#fff">
            Ontology
          </text>
          {spec.bar.meta && (
            <>
              <text x={spec.margin + 18} y={spec.bar.y + spec.bar.h / 2 + 4} fontSize="12" className="font-figures" fill="#fff" fillOpacity="0.55">
                OWL · SHACL
              </text>
              <text x={spec.width - spec.margin - 18} y={spec.bar.y + spec.bar.h / 2 + 4} fontSize="12" textAnchor="end" className="font-figures" fill="#fff" fillOpacity="0.55">
                45 classes
              </text>
            </>
          )}
        </g>

        {g.links.map((l) => (
          <g key={l.label}>
            <path className="link" d={`M${l.x1} ${l.y1} L${l.x2} ${l.y2}`} fill="none" stroke={INK} />
            <path className="arrow origin-center [transform-box:fill-box]" d={l.arrow} fill="none" stroke={INK} strokeLinejoin="round" />
            <g className="plot label">
              <text x={l.lx} y={l.ly} fontSize={f} textAnchor={l.anchor} className="font-display" fill="#404040" stroke={SHEET} strokeWidth="5" strokeLinejoin="round" paintOrder="stroke">
                {l.label}
              </text>
            </g>
          </g>
        ))}

        {g.cards.map((c) => (
          <g key={c.id}>
            <g className="plot card">
              <rect x={c.x} y={c.y} width={c.w} height={c.h} rx="4" fill="#fff" stroke={INK} />
              <text x={c.x + 10} y={c.y + 18} fontSize={f + 1} className="font-display font-semibold" fill={INK}>
                {c.title}
              </text>
              <path d={`M${c.x} ${c.y + 26} H${c.x + c.w}`} stroke={INK} strokeOpacity="0.35" />
              {c.rows.map((row, i) => (
                <text key={row} x={c.x + 10} y={c.y + 26 + 15 + i * spec.rowH} fontSize={f} className="font-figures" fill="#525252">
                  {row}
                </text>
              ))}
            </g>
            <rect className="hl-card" data-id={c.id} x={c.x - 3} y={c.y - 3} width={c.w + 6} height={c.h + 6} rx="6" fill="none" stroke={SIGNAL} strokeWidth="2" opacity="0" />
          </g>
        ))}

        <g fill="none" stroke={SIGNAL} strokeWidth="2.5" strokeLinecap="round">
          {g.cablesIn.map((d) => (
            <path key={d} className="pulse-in" d={d} opacity="0" />
          ))}
          {g.cablesOut.map((d) => (
            <path key={d} className="pulse-out" d={d} opacity="0" />
          ))}
        </g>
        {g.hops.map((h) => (
          <path key={h.key} className="hop" d={h.d} fill="none" />
        ))}
        <g mask={`url(#${spec.key}-packet)`}>
          <circle className="packet" r="5" fill={SIGNAL} opacity="0" />
        </g>
      </svg>
    </div>
  );
}

const POINTS = [
  "Modeled in OWL, constrained with SHACL",
  "Versioned in Git and reviewed like code",
  "Served from Neo4j beside the PostgreSQL ledger",
];

export function Ontology() {
  return (
    <section id="ontology" className="scroll-mt-16 overflow-hidden bg-black px-4 py-24 text-white sm:px-6 sm:py-32 lg:px-8">
      <div className="mx-auto grid max-w-[1320px] items-center gap-16 xl:grid-cols-[0.85fr_1.15fr]">
        <Reveal>
          <p data-anim className="font-figures text-xs uppercase tracking-[0.18em] text-white/50">
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
        </Reveal>

        <div className="mx-auto w-full max-w-[600px]">
          <Schematic spec={DESKTOP} className="hidden sm:block" />
          <Schematic spec={PHONE} className="mx-auto max-w-[430px] sm:hidden" />
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
