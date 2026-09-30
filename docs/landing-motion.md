# Landing motion — hero convergence and ontology system

Project copy of the motion-studio templates (brief, style guide, shotlist, review) for the two
motion pieces on the landing page: the hero "octo" (`web/components/landing/hero.tsx`) and the
ontology system schematic (`web/components/landing/ontology.tsx`).

The page is an interactive product, not an MP4. Frames are captured from the live page with
Playwright's fake clock (`page.clock`), which plays the role of the blueprint's `window.seek(t)`:
time only advances when the harness steps it. Anything driven by a real-time clock (CSS
animations, timers) is banned from the two pieces for that reason.

## Brief

**Purpose.** Make private-equity operators and allocators _see_ that disconnected sources become
one governed model, and _feel_ that it is ordered and under control.

**Logline.** Every source reaches into one book of record; the ontology is the system every
decision reads through.

**Hook (first 2 s).** The headline rises while eight sources extend into the centre, and the
ledger springs into existence only when they arrive — cause before effect.

**Metaphor (ontology section).** The ontology as an operating layer: sources plug in above, a live
model of linked objects sits in the middle, decisions plug out below — and a question travels
through it, object by object, along real relations.

**Durations.** Hero: 2.0 s intro, then a 3.2 s loop. Schematic: 3.5 s intro on scroll-in, then one
4.0 s question per loop step (three questions, 12 s cycle).

**Visual identity.** Black page; pearl-and-chrome Cycles renders in the hero; a light line-art
sheet in the ontology section. Palette `#000`, `#fff`, neutral greys, one accent (signal blue,
`oklch(64% 0.19 256)`, with a pale tint for panel headers). Display and UI face Geist, editorial
face Newsreader, figures Geist Mono. Banned: generic glow, gradients behind titles,
fade-in-everything, corner labels.

**Motion.** Closed-form springs instead of easing curves (`web/components/landing/motion.tsx`):
snappy `k260/d24` for UI and labels (settles 0.88 s), standard `k170/d26` critically damped for
type, cards and pulses (0.96 s), playful `k130/d18` only for arrivals (0.98 s), long `k40/d12.6`
for draws and travel (1.78 s). Rotation is linear because that is its physics. Loops are pinned to
an exact period and every tween ends inside it, so first and last states match.

**Audio.** None — the page ships silent. Sound sync is scored N/A.

## Style guide

### References (transfer the grammar, never the content)

| Reference | Traits transferred | Not copied | Permission |
| --- | --- | --- | --- |
| [ondo.finance](https://ondo.finance/) | Black/white section rhythm, tight grotesk headlines, serif lead copy, pill buttons, odometer stats, numbered list | Copy, products, figures, testimonials, imagery | Reference only |
| Palantir "3A-Ontology" diagram ([SVG](https://images.ctfassets.net/xrfr7uokpv1b/5QZ3Ot5MxlxI41R5uoIuEa/5c52b77ca76db2305d6dfcc9669a8b75/3A-Ontology__1_.svg)) | Hairline schematic strokes, objects drawn as small tables, labelled links between objects, dashed connectors to the ontology | The 3×3 composition, the Properties/Functions/Actions/Automations taxonomy, the icon, the lavender panel | Reference only; third-party asset |
| Palantir "Personas" illustration ([PNG](https://www.palantir.com/assets/xrfr7uokpv1b/4TF81x78iYQqD1IwhIBdt5/cc9f3506607bd520d73d624538445843/Personas.png?quality=70&width=1600)) | Flat line-art sheet, tinted header bars on each group, square icon tiles, bundled parallel cables, a solid black "Ontology" node | The inverted-T team layout, persona avatars, team names, the mint accent | Reference only; third-party asset |

Composition differs from both references on purpose: a vertical flow (sources → ontology → decisions)
whose content is OCTO's own model — classes and SHACL-bound properties from
`ontology/octo-investment-*.ttl`, and the reified relations FundManagement, Commitment,
FundInvestment and DealSubject.

### Camera and finishing (Blender scene `OCTO_Landing`, `build/landing-renders/octo-landing.blend`)

| Field | Hero setup C01 (`hero-arms.webp`, `hero-hub.webp`) |
| --- | --- |
| Colour profile | Captured: sRGB display, AgX view transform |
| Log profile | N/A — synthetic render, no log encoding |
| Shutter / FPS | N/A — stills, motion blur off; the page animates at display rate |
| ISO / EI | N/A — synthetic |
| Aperture | N/A — depth of field off |
| Focal length | Captured: orthographic, ortho scale 6.4 (1 unit = 100 SVG px) |
| Lens type | Simulated: ideal pinhole, no distortion |
| Camera angle | Captured: overhead, z = 20, looking straight down |
| DoP | N/A |
| Colour correction | Exposure 0, gamma 1 — no technical correction |
| Colour grade | Captured: look "AgX – Medium High Contrast"; key 900 W white, rim 700 W blue, rim 300 W cool |
| Transitions | Mask draw → hub spring (see shotlist) |

Render: Cycles, 128 samples, denoised, transparent film, 1200 px, exported to WebP in two layers
(tentacles, hub) that recomposite to the single-pass render within 0.04% of pixels. The geometry is
the same quadratic curves as `ARMS` in `hero.tsx`, so the SVG overlay (mask strokes, filaments,
packets) rides the rendered tubes exactly. Change one, re-render the other.

### Traits

| Trait | Rule |
| --- | --- |
| Palette | Black page, white type, one signal-blue accent per piece (filaments, packets, highlights) |
| Typography | Labels ≥ 12 px as rendered on a 375 px phone. SVG layouts are art-directed per breakpoint (600 and 343 unit viewBoxes) so type never scales below that |
| Composition | Desktop: copy left, piece right (schematic below copy until `xl`). Phone: recomposed, not scaled |
| Event cadence | A new visual event every 2–4 s in each loop (packet wave 3.2 s; question 4.0 s) |
| Camera | Static page camera; the only camera move is baked into the renders |
| Texture | None on type; chrome and pearl only in the hero renders |

### Techniques (three, each with a job)

| Technique | Where | Why the viewer needs it | Simpler move rejected |
| --- | --- | --- | --- |
| Masking ([Eyecannndy](https://eyecannndy.com/technique/masking)) | Hero 0.56–1.6 s; schematic plotting 0–3.5 s | Tentacles are revealed by strokes drawn along their own paths, and the schematic is plotted top-down — the viewer watches things being connected rather than appearing | Fading and scaling everything in, which read as decoration (pass 0) |
| Kinetic typography ([Eyecannndy](https://eyecannndy.com/technique/typography)) | Hero 0–1.4 s | Headline and lead words, source labels, the eyebrow and the CTAs rise out of a baseline mask; type arrives rather than appears | Opacity fades — the banned "fade-in-everything" |
| Shape transition ([Jonti Rudd](https://www.jontirudd.com/post/shape-transitions)) | Hero 1.22 s; schematic 1.0 s | One shape forms when its inputs arrive: the hub when the tentacles converge, the Ontology bar when the source cables reach it | Showing the core from the start, which stated the result before the cause |

Rejected: parallax (no depth story to tell), morphing (no second state), whip pan (no scene change).

**Identity lock.** Octo = pearl hub + eight tapered chrome tentacles curling a quarter-turn inward.
The Mesta mark appears only as the supplied wordmark in the nav and footer; the octo is an
illustration, never a redrawn logo.

**Transition object.** The core. The hero's hub forms when its sources arrive; the ontology
section repeats that physical grammar with the Ontology bar, so the two pieces read as one system.

### Crew and toolchain

| Role | Credit |
| --- | --- |
| Director, DoP, colourist, compositing, sound | N/A — no credited crew |

| Tool | Used for |
| --- | --- |
| Blender 5.2.2 LTS (Cycles, Metal GPU) via the Blender MCP server | Octo renders |
| anime.js 4.5 | All page motion (timelines, springs, drawables, motion paths, scroll observers, text splitting) |
| lucide-react | Line icons on the schematic tiles |
| Playwright 1.55.1 `page.clock` + Pillow | Deterministic frame capture, contact sheets, strips, phone, loop and legibility proofs |

## Shotlist

Times are milliseconds from hydration (hero) or from scroll-in (schematic). Desktop 1440 × 900 and
phone 375 × 812 run the same timeline on art-directed layouts.

| Scene | Time | Truth | Visual | Motion / exit | Transition object |
| --- | --- | --- | --- | --- | --- |
| H1 Hook | 0–700 | One book of record | Eyebrow, then headline words rise from their baseline | Snappy / standard, 70 ms stagger, no overshoot on type | Last word lands as the sources appear |
| H2 Sources | 300–800 | Eight real source families | Beads pop at the eight source positions; labels rise beside them | Snappy, clockwise stagger | Each bead is where its tentacle starts |
| H3 Reach | 560–1600 | Sources feed the ledger | Tentacles grow bead to centre (mask draw) with the blue filament drawn in sync | Long spring, 60 ms stagger | Tips meet at the centre |
| H4 Arrival | 1220–1800 | The ledger is what the sources form | Pearl hub springs into existence; "IBOR" rises inside it | Playful spring | Hub |
| H5 Flow (loop) | every 3200 from 2000 | Data keeps arriving | A wave of eight packets rides the tentacles; the hub absorbs it and ripples | Long / snappy; pinned 3.2 s period | Wave → next wave |
| S1 Sheet | 0–700 | A system, drawn | The sheet unrolls; the Sources panel plots and its six tiles pop | Long / standard / snappy | Tiles |
| S2 Plug in | 450–1300 | Sources feed one model | Cables draw from each source down to the ontology | Long spring, 40 ms stagger | Cable tips |
| S3 Ontology | 1000–1500 | One governed model | The black Ontology bar grows from the centre when the cables reach it | Playful spring | Bar |
| S4 Model | 1200–2300 | Real objects, real relations | Object cards plot; links draw with arrows; relation names appear | Standard spring, 90 ms stagger | Links |
| S5 Plug out | 2000–3500 | Every decision reads the same model | Cables draw to the Decisions panel; its tiles pop | Long / standard / snappy | Decision tiles |
| S6 Question (loop) | every 4000 after S5 | It is a graph you can ask | A source lights and pulses into the ontology; a packet crosses the model object by object along real relations; the answer pulses out to a decision | Snappy / standard; pinned 12 s cycle, all lights off at each boundary | Next question |

Questions (desktop): Fund admin → Limited partner → Fund → Deal → Operating company → LP reports;
CRM → Fund manager → Fund → Deal → Screening; Market data → Fund → Deal → Investment → Analytics.
The phone uses the same model as a vertical chain with the four cards it shows.

## Review log

Scored 1–10 from rendered evidence in `build/landing-motion/<pass>/`: per-beat contact sheets,
a 12-frame strip at 60 fps around the fastest action, phone proofs, and probes for rendered label
size, off-screen labels, pairwise text overlap, same-procedure determinism and loop seams. Sound
sync is N/A (silent page). The gate is 8+ on every scored criterion for three consecutive passes.

| Pass | Hook | Phone | Motion | Variety | Composition | Brand | Proofs |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0 (baseline) | 3 | 2 | 3 | 5 | 5 | 6 | labels 5.4–10 px on phone; orbit labels overlap; determinism fails (CSS `animate-pulse`) |
| 1 | 6 | 8 | 6 | 8 | 7 | 8 | labels ≥ 12 px, 0 overlaps; determinism off by 1/255 raster noise |
| 2 | 8 | 8 | 7 | 8 | 8 | 8 | determinism exact (software raster) |
| 3 | 8 | 9 | 8 | 8 | 8 | 8 | all probes pass |
| 4 | 8 | 9 | 8 | 8 | 8 | 8 | loop seams exact: hero wave state and schematic frame match one period later |
| 5 | 8 | 9 | 8 | 8 | 8 | 8 | all probes pass; verification re-render, no code changes |

Worst problems found, and their fixes:

- **Pass 0.** (1) Hero 0.00–0.38 s: the headline painted fully, then each word snapped down and
  re-rose → start states set before first paint. (2) Phone: hero labels 5.4–8 px, orbit 10 px → HTML
  labels ≥ 12 px, "one ledger" dropped as a duplicate of the headline. (3) Orbit: everything faded
  in at once, rings drawn full white (the `ring` class collided with Tailwind's `ring` utility),
  labels overlapped near 3 and 9 o'clock → orbit replaced by the ontology system schematic.
- **Pass 1.** (1) Hero 1.42–2.70 s: the hub never arrived — the wave loop animated the same
  property on the same element and replaced the intro spring → the wave bumps a wrapper. (2)
  Schematic 0–0.3 s: an empty grey sheet → the sheet unrolls with the first plot. (3) Eyebrow and
  CTAs still faded → they rise from a padded mask that keeps focus rings visible.
- **Pass 2.** (1) Hero 0–0.2 s: 3–5 px of the eyebrow and CTAs peeked above their masks → start at
  160 %. (2) Hero 1.2–1.45 s: an empty centre before the hub → arrival at 1.22 s. (3) The packet
  and pulses were too faint to carry the metaphor → larger packet, heavier pulse.
- **Pass 3.** (1) Hub timing nit (≈100 ms) → tightened. (2) The third desktop question began at an
  object with no link to the Ontology bar → it now starts at Fund. (3) Loop period drifted to
  11.15 s because spring tails outlived the grid → measured settle times, 4 s questions, period
  pinned.

Known limitations:

- Determinism is exact when a frame is rendered twice by the same procedure. Reaching the same
  time by different seek paths can shift the rising lead words by a sub-pixel, because anime.js's
  text splitter reacts to a `ResizeObserver` (real time) through a `setTimeout` (fake time). A real
  browser has one timeline, and a re-split can only reset words to their final, visible positions.
- The other sections (stats, platform, security, FAQ, contact) still share one lift-in reveal per
  element. They are outside this brief and the next candidates for a pass.
