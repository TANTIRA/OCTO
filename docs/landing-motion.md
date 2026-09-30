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
| Vadim Sadovski, "OCTOPUS – NASA mech" (Behance) | Rendering fidelity and detail density: hard-surface panels, wear, cables, lights, drive hardware | Every signature element (wedge hull, 2×2 visor lights, ribbon tentacles, claws, antennas, NASA marks, poster type). A 1:1 copy was requested and declined: it is the artist's copyrighted design | Reference only; third-party asset. The images live in gitignored `build/references` and never ship |
| [palantir.com](https://www.palantir.com/) hero | One centred headline alone on black; the copy blurs and lifts away as the page scrolls | Copy, type, video | Reference only |

Composition differs from both references on purpose: a vertical flow (sources → ontology → decisions)
whose content is OCTO's own model — classes and SHACL-bound properties from
`ontology/octo-investment-*.ttl`, and the reified relations FundManagement, Commitment,
FundInvestment and DealSubject.

### Camera and finishing (Blender scene `OCTO_Landing`, `build/landing-renders/octo-landing-core.blend`)

| Field | Hero dock poster (`renders/hero-core.webp`, from `mecha_build.py` + `mech_poster.py`) |
| --- | --- |
| Colour profile | Captured: sRGB display, AgX view transform |
| Log profile | N/A — synthetic render, no log encoding |
| Shutter / FPS | N/A — still, motion blur off; the live core animates at display rate |
| ISO / EI | N/A — synthetic |
| Aperture | N/A — depth of field off |
| Focal length | Captured: 85 mm on a 36 mm sensor (horizontal fit), `CORE_Cam` |
| Lens type | Simulated: ideal pinhole, no distortion |
| Camera angle | Captured: high three-quarter, 80° elevation — the live scene's `POSTER_ELEV` matches it |
| DoP | N/A |
| Colour correction | Exposure 0, gamma 1 — no technical correction |
| Colour grade | Captured: look "AgX – Medium High Contrast"; soft top key, cool rim from behind, low fill (see `mecha_build.py`); Cycles 160 samples, denoised, bloom in `MECH_Comp` |
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

## Live ledger core — the octo crawls the page (supersedes the SVG hero convergence)

The hero graphic is now a real-time 3D object. `OctoCore` (`web/components/landing/octo-core.tsx`)
mounts a fixed, transparent, `pointer-events: none` canvas (z-30, between each section's background
and its content at z-40, under the nav at z-50) and lazy-loads `octo-core-scene.ts` (three.js) on
idle. The hero keeps a Cycles poster of the same pose in `[data-octo-dock]` for first paint; the
canvas fades in once it has drawn a frame and the poster's reveal has finished. Reduced motion (at
load or switched on later), no WebGL, a failed load or a lost context all leave the poster.

**The page is the wall.** Each arm tip grips a point of the page (stored as `x, y + scrollY`), so a
gripping tip moves exactly with the content under it. `spider-gait.ts` (pure, tested:
`node --test web/components/landing/spider-gait.test.mjs`) decides when a foot lifts and where it
lands: an alternating tetrapod on the ring of arms, Cruse's coordination rules (never two
neighbours in swing, at most four), duty factor 0.75 → 0.5 with speed, Raibert placement, and
minimum-jerk swings that meet the page at zero speed. When it runs, each leg steps once per group
turn, and a leg that has to step holds its neighbours down, so neither group can starve the other. The scene clamps a grip to 1.5× arm length,
dresses every arm each frame from the Blender parts (`Kit_*`) along a FABRIK-constrained verlet chain,
and runs data packets from each gripping tip into the core.

**Pace.** It only ever crawls. A leash walks the body towards its choreographed spot no faster
than the legs can carry it (6 model units/s); across copy, far behind or out of view the gait
hurries, up to 1.6x (the gait keeps every planted foot within reach up to ~10 units/s), but the
octo never leaves the wall. Out of sight it is carried along the edge of the view with its feet
reset, so after a fast scroll or an anchor jump, even on a long phone page, it crawls back in from
just outside the view. Nobody sees the carry.

**Never on the copy.** Every run of text and every opaque card (`main article, form, [role=img]`) is a
keep-out box, measured at layout (sticky copy once per frame). Each frame the scene scores spots
around its choreographed one (copy covered, distance from the path, staying on screen below the
nav, and at least half the body in view) and takes the cheapest. On a phone, where copy spans
the width, it peeks in from the margin. Resting spots (`STOPS`) are empty page: beside the stats heading, the
right margin by the product cards, under the ontology list, the lower half of the security intro,
under the FAQ heading, under the contact steps, and beside the footer wordmark.

**Budget.** Body ≤ 80k triangles, `octo-core.glb` ≤ 2.5 MB with no Draco/meshopt (the CSP has no
`wasm-unsafe-eval`; textures load through `<img>` because `connect-src` has no `blob:`). DPR ≤ 1.5
(lite 1.25), adaptive in 2 s windows; 60 fps cap on phones; sleeps after 4.5 s without movement
(WCAG 2.2.2) and wakes on scroll. Measured (M5, Metal, wheel scrolling,
`build/landing-motion/core-crawl.mjs`): octo CPU p50 1.2 ms on desktop and 3.2 ms on a phone with
4× CPU throttle; frame p95 16.8 ms at 60 Hz on both; foot slip p95 0 px; every resting spot on
screen at 1440, 1200 and 390 px.

**Pipeline.** Model and poster are built in Blender from `build/landing-renders/` (gitignored):
`LOD = 0.5; mecha_build.py; mech_glb.py` → `web/public/models/octo-core.glb`, and
`LOD = 1.0; mecha_build.py; mech_poster.py` → `web/public/renders/hero-core.webp`. The arm rest pose
in `mecha_build.py` (`rest_line`, `gripping_line`) mirrors `restPose`/`reach` in
`octo-core-scene.ts` constant for constant, so the poster and the first live frame agree. Probes:
`build/landing-motion/core-crawl.mjs` (slip, gait, CPU) with `?octo-debug`.

