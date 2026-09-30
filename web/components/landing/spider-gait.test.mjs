// node --test web/components/landing/spider-gait.test.mjs   (Node >= 23: native TS stripping)
import assert from "node:assert/strict";
import { test } from "node:test";
import { createSpiderGait } from "./spider-gait.ts";

const N = 8;
const RING = 80; // px from body centre to each leg's home
const STEP_DIST = 26;
const REACH = 120;

const homesAt = (p) =>
  Array.from({ length: N }, (_, i) => {
    const a = p.heading + (i * Math.PI * 2) / N;
    return { x: p.x + RING * Math.cos(a), y: p.y + RING * Math.sin(a) };
  });

/** Drive the gait along path(t) at a fixed frame rate; return per-frame records. */
function run(path, { seconds = 4, fps = 60 } = {}) {
  const gait = createSpiderGait({ legs: N });
  gait.reset(homesAt(path(0)));
  const frames = [];
  const dt = 1 / fps;
  for (let k = 1; k <= Math.round(seconds * fps); k++) {
    const t = k * dt;
    const p = path(t);
    const q = path(t - 1e-3);
    const velocity = { x: (p.x - q.x) / 1e-3, y: (p.y - q.y) / 1e-3 };
    const homes = homesAt(p);
    const { feet } = gait.update(dt, { homes, velocity, stepDist: STEP_DIST, reach: REACH });
    frames.push({ homes, feet: feet.map((f) => ({ ...f })) });
  }
  return frames;
}

function invariants(frames, label) {
  let maxOff = 0; // planted feet: the contract ("never sits further than reach")
  let maxSwingOff = 0; // swinging feet lag briefly while they accelerate from rest
  let maxMove = 0;
  let steps = 0;
  for (let k = 0; k < frames.length; k++) {
    const { feet, homes } = frames[k];
    const swinging = feet.map((f) => !f.planted);
    assert.ok(swinging.filter(Boolean).length <= 4, `${label}: more than 4 legs swinging at frame ${k}`);
    for (let i = 0; i < N; i++) {
      const f = feet[i];
      assert.ok(Number.isFinite(f.x) && Number.isFinite(f.y) && Number.isFinite(f.lift), `${label}: NaN at ${k}/${i}`);
      assert.ok(!(swinging[i] && swinging[(i + 1) % N]), `${label}: neighbours ${i} and ${(i + 1) % N} swing together at frame ${k}`);
      const off = Math.hypot(f.x - homes[i].x, f.y - homes[i].y);
      if (f.planted) maxOff = Math.max(maxOff, off);
      else maxSwingOff = Math.max(maxSwingOff, off);
      assert.ok(f.lift >= 0 && f.lift <= 1, `${label}: lift out of range`);
      if (k) {
        const p = frames[k - 1].feet[i];
        if (p.planted && f.planted) assert.deepEqual([f.x, f.y], [p.x, p.y], `${label}: planted foot ${i} slipped at frame ${k}`);
        if (p.planted && !f.planted) steps++;
        maxMove = Math.max(maxMove, Math.hypot(f.x - p.x, f.y - p.y));
      }
    }
  }
  assert.ok(maxOff <= REACH, `${label}: a planted foot sat ${maxOff.toFixed(1)} px from home (reach ${REACH})`);
  assert.ok(maxSwingOff <= 1.5 * REACH, `${label}: a swinging foot trailed ${maxSwingOff.toFixed(1)} px`);
  return { maxOff, maxSwingOff, maxMove, steps };
}

const line = (speed) => (t) => ({ x: speed * t, y: 0.35 * speed * t, heading: 0 });

test("walking: zero slip, <= 4 swinging, never two neighbours, within reach", () => {
  for (const speed of [60, 250, 500]) {
    for (const fps of [60, 120, 144]) {
      const r = invariants(run(line(speed), { fps }), `${speed}px/s @${fps}fps`);
      assert.ok(r.steps > 0, "it walked");
    }
  }
});

test("no teleports: per-frame foot travel stays bounded", () => {
  const { maxMove } = invariants(run(line(500), { fps: 60 }), "500px/s");
  // fastest minimum-jerk swing: 1.875 * (2 * reach) / 0.16 s, per 1/60 s frame
  assert.ok(maxMove < (1.875 * 2 * REACH) / 0.16 / 60, `a foot moved ${maxMove.toFixed(1)} px in one frame`);
});

test("turning in place and wandering curves keep every invariant", () => {
  invariants(run((t) => ({ x: 0, y: 0, heading: 1.4 * t })), "spin");
  invariants(run((t) => ({ x: 220 * Math.sin(t), y: 160 * Math.sin(1.7 * t), heading: 0.6 * Math.sin(0.8 * t) }), { seconds: 6 }), "wander");
});

test("deterministic: the same input gives the same feet", () => {
  const a = JSON.stringify(run(line(250)));
  const b = JSON.stringify(run(line(250)));
  assert.equal(a, b);
});

test("at rest the feet tidy back to their homes", () => {
  const path = (t) => (t < 1.5 ? line(250)(t) : line(250)(1.5));
  const frames = run(path, { seconds: 4 });
  const { feet, homes } = frames.at(-1);
  feet.forEach((f, i) => {
    assert.ok(f.planted, `leg ${i} still swinging at rest`);
    assert.ok(Math.hypot(f.x - homes[i].x, f.y - homes[i].y) <= 0.3 * STEP_DIST, `leg ${i} not tidied`);
  });
});

test("a scroll jump + reset() never produces NaN, even with garbage dt and velocity", () => {
  const gait = createSpiderGait({ legs: N });
  gait.reset(homesAt({ x: 0, y: 0, heading: 0 }));
  gait.update(1 / 60, { homes: homesAt({ x: 5, y: 0, heading: 0 }), velocity: { x: 300, y: 0 }, stepDist: STEP_DIST, reach: REACH });
  const far = homesAt({ x: 0, y: 48000, heading: 2 });
  gait.reset(far);
  for (const dt of [NaN, -1, 0, 5, 1 / 60]) {
    const { feet } = gait.update(dt, { homes: far, velocity: { x: NaN, y: Infinity }, stepDist: STEP_DIST, reach: REACH });
    for (const f of feet) assert.ok(Number.isFinite(f.x) && Number.isFinite(f.y) && Number.isFinite(f.lift) && Number.isFinite(f.phase));
  }
});

test("fast crawls in every direction never starve a group (the scene's proportions)", () => {
  // OctoCore: unit u, homes ~3u out, stepDist 0.6u, reach 1.5u, up to ~11u/s.
  // A group used to re-lift in a staggered loop and leave the other planted for
  // seconds, dragged far past its reach.
  const u = 45;
  for (const speed of [4 * u, 8 * u, 11 * u]) {
    for (let k = 0; k < 24; k++) {
      const head = (k * Math.PI) / 12;
      const gait = createSpiderGait({ legs: N });
      const at = (x, y, t) => Array.from({ length: N }, (_, i) => {
        const a = 0.1 * t + (i * Math.PI * 2) / N;
        return { x: x + 3 * u * Math.cos(a), y: y + 2.4 * u * Math.sin(a) };
      });
      gait.reset(at(0, 0, 0));
      let x = 0, y = 0, longest = 0;
      const stance = new Array(N).fill(0);
      for (let f = 1; f <= 360; f++) {
        const v = speed * Math.min(1, f / 30);
        x += (v * Math.cos(head)) / 60;
        y += (v * Math.sin(head)) / 60;
        const homes = at(x, y, f / 60);
        const { feet } = gait.update(1 / 60, { homes, velocity: { x: v * Math.cos(head), y: v * Math.sin(head) }, stepDist: 0.6 * u, reach: 1.5 * u });
        feet.forEach((ft, i) => {
          stance[i] = ft.planted ? stance[i] + 1 : 0;
          longest = Math.max(longest, stance[i]);
          if (ft.planted) assert.ok(Math.hypot(ft.x - homes[i].x, ft.y - homes[i].y) <= 1.5 * u, `leg ${i} past reach at ${speed / u}u/s, heading ${head.toFixed(2)}`);
        });
      }
      if (speed > 4 * u) assert.ok(longest < 45, `a leg stood ${longest} frames at ${speed / u}u/s, heading ${head.toFixed(2)}`);
    }
  }
});

test("driven like the scene (spring after a leash, 60 and 144 fps) a planted foot overshoots reach by at most 12%", () => {
  // octo-core-scene.ts: critically damped spring (w = 9) chasing a leash at
  // up to 9.6 units/s from rest. The forced step waits for both neighbours to
  // land, so a foot can drift past reach while the body accelerates; this pins
  // that bound so a gait change can't quietly make it worse.
  const u = 45;
  let worst = 0;
  for (const fps of [60, 144]) {
    for (const reach of [1.34 * u, 1.5 * u]) {
      for (let k = 0; k < 8; k++) {
        const head = (k * Math.PI) / 4;
        const gait = createSpiderGait({ legs: N });
        const p = { x: 0, y: 0, vx: 0, vy: 0 };
        const leash = { x: 0, y: 0 };
        const dt = 1 / fps;
        const at = (t) => Array.from({ length: N }, (_, i) => {
          const a = 0.1 * t + (i * Math.PI * 2) / N;
          return { x: p.x + 3 * u * Math.cos(a), y: p.y + 2.4 * u * Math.sin(a) };
        });
        gait.reset(at(0));
        for (let f = 1; f <= fps * 3; f++) {
          leash.x += Math.cos(head) * 9.6 * u * dt;
          leash.y += Math.sin(head) * 9.6 * u * dt;
          p.vx += (81 * (leash.x - p.x) - 18 * p.vx) * dt;
          p.vy += (81 * (leash.y - p.y) - 18 * p.vy) * dt;
          p.x += p.vx * dt;
          p.y += p.vy * dt;
          const homes = at(f * dt);
          const { feet } = gait.update(dt, { homes, velocity: { x: p.vx, y: p.vy }, stepDist: 0.6 * u, reach });
          feet.forEach((ft, i) => {
            if (ft.planted) worst = Math.max(worst, Math.hypot(ft.x - homes[i].x, ft.y - homes[i].y) / reach);
          });
        }
      }
    }
  }
  assert.ok(worst <= 1.12, `a planted foot sat ${worst.toFixed(3)}x reach from home`);
});
