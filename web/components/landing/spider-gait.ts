// Spider gait for OctoCore's eight radial legs, in page px (x, y + scrollY).
// The caller owns the body; this decides when each foot lifts, where it lands
// and how it travels. Pure and deterministic: no DOM, no three.js, no randomness.
//
// How spiders walk, as the rules enforced here:
// - Stance feet grip the substrate: a planted foot never moves (zero slip).
// - Alternating tetrapod on a ring: neighbours are (i ± 1) mod n, so even and
//   odd legs take turns, at most four swing, and two neighbours never do.
// - Cruse's coordination rules decide lift-off: (a) a foot steps once it has
//   drifted stepDist from its home, soonest when it lags behind along the
//   velocity (its posterior extreme); (b) never while a neighbour is in swing;
//   and a neighbour's touchdown lowers the threshold for a moment (excitation),
//   which runs the metachronal wave around the ring.
// - Speed sets cadence and stride: duty factor 0.75 -> 0.5 and swing time
//   0.3 s -> 0.16 s as the body speeds up. Past a trot, the groups couple into a
//   strict alternating tetrapod (only the swinging parity may join), the way
//   spiders stop wave-stepping and alternate when they run.
// - Raibert placement: land at home-at-touchdown + velocity * stance / 2, so the
//   foot passes under its home mid-stance; capped at 0.55 * reach from home.
// - Minimum-jerk travel and a lift bump flat at both ends: a foot leaves and
//   meets the page with zero velocity, so contacts never pop.
// - A foot stays within reach: past 0.9 * reach it is forced to step, and a
//   neighbour still in swing is hurried down so (b) holds. Accelerating from
//   rest to full speed it can overshoot by up to ~10% before its turn comes
//   (the forced step still waits for both neighbours to land).
// Fixed 120 Hz substeps with a clamped accumulator: frame-rate independent,
// and a resumed tab never fast-forwards.

export type Vec2 = { x: number; y: number };
export type GaitFoot = { x: number; y: number; lift: number; planted: boolean; phase: number };
export type GaitInput = { homes: Vec2[]; velocity: Vec2; stepDist: number; reach: number };

const STEP = 1 / 120;
const MAX_SWING = 4;
const SWING_SLOW = 0.3; // s
const SWING_FAST = 0.16;
const DUTY_SLOW = 0.75;
const DUTY_FAST = 0.5;
const FAST_STEPS = 8; // stepDists per second at which the gait is fully fast
const PLACE_CAP = 0.55; // of reach
const LAG_WEIGHT = 1.25; // lagging along -velocity counts extra (posterior extreme)
const EXCITE_WINDOW = 0.08; // s after a neighbour's touchdown ...
const EXCITE = 0.7; // ... the lift threshold shrinks by this factor
const IDLE_SPEED = 0.05; // stepDists per second: below this the body is at rest
const IDLE_AFTER = 0.35; // s at rest before feet tidy back toward home
const IDLE_TRIGGER = 0.25; // of stepDist
const FORCE = 0.9; // of reach: step out of turn
const HURRY = 2; // swing-rate multiplier for a neighbour blocking a forced step
const RETARGET_UNTIL = 0.6; // swing progress after which the landing point is frozen
const COUPLE_FROM = 0.35; // "fast" level from which the two groups step strictly in turn

type Leg = {
  x: number;
  y: number;
  planted: boolean;
  u: number; // swing progress 0..1
  rate: number; // 1 / swing duration
  sx: number; // swing start
  sy: number;
  ex: number; // landing point
  ey: number;
  down: number; // time of the last touchdown
};

const minJerk = (u: number) => u * u * u * (10 + u * (-15 + 6 * u));
const bump = (u: number) => 16 * u * u * (1 - u) * (1 - u); // 0 with zero slope at both ends, 1 at u = 0.5
const finite = (v: number) => (Number.isFinite(v) ? v : 0);

export function createSpiderGait({ legs: n }: { legs: number }) {
  const legs: Leg[] = Array.from({ length: n }, () => ({
    x: 0, y: 0, planted: true, u: 0, rate: 1 / SWING_SLOW, sx: 0, sy: 0, ex: 0, ey: 0, down: -Infinity,
  }));
  const feet: GaitFoot[] = legs.map(() => ({ x: 0, y: 0, lift: 0, planted: true, phase: -1 }));
  const held = new Uint8Array(n);
  let time = 0;
  let acc = 0;
  let still = 0;
  let turn = -Infinity; // when the group now in the air began its turn

  /** Snap every foot to its home: first frame, resize, or a scroll jump. */
  function reset(homes: Vec2[]) {
    for (let i = 0; i < n; i++) {
      Object.assign(legs[i], { x: homes[i].x, y: homes[i].y, planted: true, u: 0, down: -Infinity });
    }
    acc = 0;
    still = IDLE_AFTER;
    return snapshot();
  }

  function landing(home: Vec2, vx: number, vy: number, left: number, stanceT: number, reach: number) {
    // home keeps moving with the body until touchdown, then half a stance ahead
    const dx = vx * stanceT * 0.5;
    const dy = vy * stanceT * 0.5;
    const d = Math.hypot(dx, dy);
    const k = d > PLACE_CAP * reach ? (PLACE_CAP * reach) / d : 1;
    return { x: home.x + vx * left + dx * k, y: home.y + vy * left + dy * k };
  }

  function step(h: number, { homes, velocity, stepDist, reach }: GaitInput) {
    time += h;
    const vx = finite(velocity.x);
    const vy = finite(velocity.y);
    const speed = Math.hypot(vx, vy) / Math.max(stepDist, 1e-6);
    const fast = Math.min(speed / FAST_STEPS, 1);
    const swingT = SWING_SLOW + (SWING_FAST - SWING_SLOW) * fast;
    const duty = DUTY_SLOW + (DUTY_FAST - DUTY_SLOW) * fast;
    const stanceT = (swingT * duty) / (1 - duty);
    still = speed < IDLE_SPEED ? still + h : 0;
    const vl = Math.hypot(vx, vy);
    const ux = vl > 1e-6 ? vx / vl : 0;
    const uy = vl > 1e-6 ? vy / vl : 0;

    // which planted legs want to lift, and which must
    const want: Array<[number, number, boolean]> = [];
    for (let i = 0; i < n; i++) {
      const L = legs[i];
      if (!L.planted) continue;
      const ex = L.x - homes[i].x;
      const ey = L.y - homes[i].y;
      const off = Math.hypot(ex, ey);
      const lag = -(ex * ux + ey * uy); // > 0 when the foot trails its home
      const urge = Math.max(off, lag * LAG_WEIGHT) / stepDist;
      const excited = time - legs[(i + n - 1) % n].down < EXCITE_WINDOW || time - legs[(i + 1) % n].down < EXCITE_WINDOW;
      const trigger = still >= IDLE_AFTER ? IDLE_TRIGGER : excited ? EXCITE : 1;
      const forced = off > FORCE * reach;
      if (forced || urge > trigger) want.push([forced ? Infinity : urge, i, forced]);
    }
    // a forced leg hurries its swinging neighbours down so it can go next
    for (const [, i, forced] of want) {
      if (!forced) continue;
      for (const j of [(i + n - 1) % n, (i + 1) % n]) if (!legs[j].planted) legs[j].rate = Math.max(legs[j].rate, HURRY / swingT);
    }

    // swings: minimum-jerk travel; the landing tracks the moving home while young
    let swinging = 0;
    for (let i = 0; i < n; i++) {
      const L = legs[i];
      if (L.planted) continue;
      L.u = Math.min(L.u + h * L.rate, 1);
      if (L.u < RETARGET_UNTIL) {
        const q = landing(homes[i], vx, vy, (1 - L.u) / L.rate, stanceT, reach);
        const k = 1 - Math.exp(-h * 18);
        L.ex += (q.x - L.ex) * k;
        L.ey += (q.y - L.ey) * k;
      }
      const s = minJerk(L.u);
      L.x = L.sx + (L.ex - L.sx) * s;
      L.y = L.sy + (L.ey - L.sy) * s;
      if (L.u >= 1) {
        L.planted = true;
        L.down = time;
      } else swinging++;
    }

    // Cruse (b): most urgent first, never beside a swinging leg, at most four;
    // when running, only the parity already in the air may join it, and each
    // leg steps once per turn. Without that one group re-lifts in a staggered
    // loop and the other never finds both neighbours planted, so it starves.
    // A forced leg also holds its planted neighbours down until it has gone.
    let parity = -1;
    for (let i = 0; i < n && parity < 0; i++) if (!legs[i].planted) parity = i % 2;
    want.sort((a, b) => b[0] - a[0] || a[1] - b[1]);
    held.fill(0);
    for (const [, i, forced] of want) {
      if (swinging >= MAX_SWING) break;
      const L = legs[i];
      const a = (i + n - 1) % n;
      const b = (i + 1) % n;
      if (!L.planted || held[i]) continue;
      if (fast >= COUPLE_FROM && parity >= 0 && !forced && (i % 2 !== parity || L.down >= turn)) continue;
      if (!legs[a].planted || !legs[b].planted) {
        if (forced) held[a] = held[b] = 1;
        continue;
      }
      if (parity < 0) {
        parity = i % 2;
        turn = time;
      }
      const q = landing(homes[i], vx, vy, swingT, stanceT, reach);
      Object.assign(L, { planted: false, u: 0, rate: 1 / swingT, sx: L.x, sy: L.y, ex: q.x, ey: q.y });
      swinging++;
    }
  }

  function snapshot() {
    for (let i = 0; i < n; i++) {
      const L = legs[i];
      const f = feet[i];
      f.x = L.x;
      f.y = L.y;
      f.planted = L.planted;
      f.phase = L.planted ? -1 : L.u;
      f.lift = L.planted ? 0 : bump(L.u);
    }
    return { feet };
  }

  /** Advance dt seconds. Returns a reused snapshot; copy it if you keep it. */
  function update(dt: number, input: GaitInput) {
    acc += Math.min(Math.max(finite(dt), 0), 0.1);
    while (acc >= STEP) {
      step(STEP, input);
      acc -= STEP;
    }
    return snapshot();
  }

  return { reset, update };
}
