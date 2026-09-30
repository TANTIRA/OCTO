// The live ledger core: a three.js scene on a fixed, transparent canvas. The
// landing page is its wall. It sits in the hero's [data-octo-dock], then
// crawls down the page with the scroll: every arm tip grips a point of the
// page (so gripping tips move with the content), and when the body has moved
// on, the arm lifts, arcs and plants again ahead, four arms at a time like a
// spider's alternating gait. Between sections it rests behind cards and
// diagrams, never under running text.
// The body comes from Blender (build/landing-renders/mech_glb.py ->
// /models/octo-core.glb); the arms are verlet chains dressed every frame with
// the same Blender parts (Kit_Vertebra, Kit_Lug, Kit_Collar, Kit_Boot,
// Kit_Ferrule). Data packets run from each gripping tip into the core.
import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";
import { createSpiderGait } from "./spider-gait";

export type OctoOptions = {
  canvas: HTMLCanvasElement;
  dock: HTMLElement;
  model: string;
  field?: string;
  onReady: () => void;
};

// Blender CORE_Cam (85 mm, 80 deg elevation, 18 units from the aim point):
// the first live frame lines up with the poster render in the dock.
const POSTER_ELEV = THREE.MathUtils.degToRad(80);
const POSTER_FRAME = 7.624; // world units across the poster at the aim point (18 * 36 / 85)
const POSTER_AIM = new THREE.Vector3(0, -0.18, -0.05);
const WALL = 0.62; // body centre to the wall, in model units (the Blender floor)
const FOV = 24;
const CAM_Z = 22;
const SIGNAL = new THREE.Color(0.23, 0.48, 1.0);
const N = 22; // chain nodes per arm

// Resting places on the page. `at` is the element it rests on; ax/ay place it
// within that element's box (or x as a fraction of the viewport width), and
// size is its span as a fraction of the viewport height. Each spot is empty
// page: no running text on it and no card over it, so it stays in view.
type Spot = { at: string; ax?: number; x?: number; ay: number; size: number };
type Stop = Spot & { m?: Spot };
const STOPS: Stop[] = [
  // beside the stats heading
  { at: "#stats h2", x: 0.8, ay: 0.5, size: 0.38, m: { at: "#stats h2", x: 0.62, ay: -0.55, size: 0.24 } },
  // the open space above the short platform intro, right of the heading
  { at: "#platform p", ax: 0.6, ay: -1.9, size: 0.3, m: { at: "#platform p", x: 0.62, ay: 2.4, size: 0.24 } },
  // under the ontology intro, left of the diagram
  { at: "#ontology h2 + p", ax: 0.42, ay: 4.2, size: 0.36, m: { at: "#ontology h2 + p", x: 0.5, ay: 3.2, size: 0.24 } },
  // the empty lower half of the security intro column
  { at: "#security", ax: 0.2, ay: 0.78, size: 0.34, m: { at: "#security h2 + p", x: 0.6, ay: 2.2, size: 0.24 } },
  // the empty left column under the FAQ heading
  { at: "#faq h2", ax: 0.4, ay: 4.4, size: 0.34, m: { at: "#faq h2", x: 0.62, ay: -0.9, size: 0.24 } },
  // under the contact steps, left of the form
  { at: "#contact ol", ax: 0.4, ay: 2.4, size: 0.36, m: { at: "#contact ol", x: 0.5, ay: 1.6, size: 0.26 } },
  // beside the giant wordmark, clear of its letters
  { at: "footer .giant", x: 0.87, ay: 0.45, size: 0.36, m: { at: "footer .giant", x: 0.7, ay: -0.6, size: 0.26 } },
];

const up = new THREE.Vector3(0, 1, 0);
const zAxis = new THREE.Vector3(0, 0, 1);
const clamp01 = (v: number) => Math.min(1, Math.max(0, v));
const smooth = (a: number, b: number, v: number) => {
  if (b - a < 1e-6) return v >= b ? 1 : 0;
  const t = clamp01((v - a) / (b - a));
  return t * t * (3 - 2 * t);
};
// Additive light that leaves alpha alone: on the transparent canvas it adds
// glow over black sections and never greys out white ones.
function lightOnly<T extends THREE.Material>(m: T): T {
  m.blending = THREE.CustomBlending;
  m.blendEquation = THREE.AddEquation;
  m.blendSrc = THREE.SrcAlphaFactor;
  m.blendDst = THREE.OneFactor;
  m.blendSrcAlpha = THREE.ZeroFactor;
  m.blendDstAlpha = THREE.OneFactor;
  return m;
}

type Part = { geometry: THREE.BufferGeometry; material: THREE.Material };
type Arm = {
  rootPos: THREE.Vector3; // body space
  rootDir: THREE.Vector3;
  len: number;
  r0: number;
  r1: number;
  phase: number;
  group: number; // gait group: 0 and 1 alternate
  nodes: THREE.Vector3[];
  prev: THREE.Vector3[];
  rest: THREE.Vector3[];
  cum: Float32Array; // cumulative chord length along the nodes
  packets: number[];
  // the grip, in page px (x, y + scrollY): the wall is the page
  foot: THREE.Vector2;
  home: THREE.Vector2;
  step: number; // swing progress 0..1 while in the air, -1 when gripping
  flash: number;
  tip: THREE.Vector3; // world, for the glow
  glow: THREE.Sprite;
  ripple: THREE.Sprite;
};

function partsOf(root: THREE.Object3D | undefined): Part[] {
  const out: Part[] = [];
  root?.traverse((o) => {
    const m = o as THREE.Mesh;
    if (m.isMesh) out.push({ geometry: m.geometry, material: m.material as THREE.Material });
  });
  return out;
}

function spriteTexture(ring: boolean) {
  const c = document.createElement("canvas");
  c.width = c.height = 64;
  const g = c.getContext("2d")!;
  const grad = g.createRadialGradient(32, 32, 0, 32, 32, 32);
  if (ring) {
    grad.addColorStop(0.55, "rgba(255,255,255,0)");
    grad.addColorStop(0.8, "rgba(255,255,255,0.9)");
    grad.addColorStop(1, "rgba(255,255,255,0)");
  } else {
    grad.addColorStop(0, "rgba(255,255,255,1)");
    grad.addColorStop(0.25, "rgba(255,255,255,0.45)");
    grad.addColorStop(1, "rgba(255,255,255,0)");
  }
  g.fillStyle = grad;
  g.fillRect(0, 0, 64, 64);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

export function startOcto({ canvas, dock, model, field, onReady }: OctoOptions): () => void {
  const small = matchMedia("(max-width: 767px)").matches;
  const lite = small || (navigator.hardwareConcurrency ?? 8) <= 4;
  const renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true });
  renderer.setClearColor(0x000000, 0);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.AgXToneMapping;
  renderer.toneMappingExposure = 1.05;
  const maxDpr = Math.min(window.devicePixelRatio || 1, lite ? 1.25 : 1.5); // MSAA covers the edges
  let dpr = maxDpr;
  renderer.setPixelRatio(dpr);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(FOV, 1, 0.5, 80);
  camera.position.set(0, 0, CAM_Z);
  const pmrem = new THREE.PMREMGenerator(renderer);
  const bakeEnv = () => {
    const room = new RoomEnvironment();
    const tex = pmrem.fromScene(room, 0.04).texture;
    room.dispose();
    return tex;
  };
  let env = bakeEnv();
  scene.environment = env;
  scene.environmentIntensity = 0.5;
  const key = new THREE.DirectionalLight(0xfff3e8, 2.4);
  key.position.set(-5, 7, 8);
  const rim = new THREE.DirectionalLight(0xc4dcff, 2.2);
  rim.position.set(6, 4, -3);
  const rim2 = new THREE.DirectionalLight(0xe2eaff, 1.1);
  rim2.position.set(-7, -2, 2);
  scene.add(key, rim, rim2, new THREE.HemisphereLight(0xa9bbd4, 0x040506, 0.5));

  const octo = new THREE.Group(); // placement on the wall + orientation
  const bodyGroup = new THREE.Group(); // model space (Blender units, glTF axes)
  octo.add(bodyGroup);
  scene.add(octo);

  const glowTex = spriteTexture(false);
  const ringTex = spriteTexture(true);
  const arms: Arm[] = [];
  let ready = false;
  let disposed = false;
  let coreFlash = 0;
  let fieldMesh: THREE.Mesh | null = null;

  // --- instanced arm parts ---------------------------------------------------
  const cyl = new THREE.CylinderGeometry(1, 1, 1, 6, 1, true);
  const steel = new THREE.MeshStandardMaterial({ color: 0x8c8f94, metalness: 1, roughness: 0.3 });
  const rubber = new THREE.MeshStandardMaterial({ color: 0x050506, metalness: 0, roughness: 0.75 });
  const packetMat = new THREE.MeshBasicMaterial({ color: SIGNAL.clone().multiplyScalar(2.2) });
  const sphere = new THREE.SphereGeometry(1, 12, 8);
  const pools = new Map<string, THREE.InstancedMesh[]>();
  const instMats = new Map<THREE.Material, THREE.Material>();
  function pool(name: string, parts: Part[], max: number) {
    pools.set(name, parts.map((p) => {
      // a material shared with a plain mesh would flip the program's
      // instancing variant every draw; instanced parts get their own
      let mat = instMats.get(p.material);
      if (!mat) instMats.set(p.material, (mat = p.material.clone()));
      const m = new THREE.InstancedMesh(p.geometry, mat, max);
      m.matrixAutoUpdate = false;
      m.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
      m.frustumCulled = false;
      m.count = 0;
      scene.add(m);
      return m;
    }));
  }
  const counts = new Map<string, number>();
  function place(name: string, m: THREE.Matrix4) {
    const meshes = pools.get(name);
    if (!meshes?.length) return;
    const i = counts.get(name) ?? 0;
    if (i >= meshes[0].instanceMatrix.count) return;
    for (const mesh of meshes) mesh.setMatrixAt(i, m);
    counts.set(name, i + 1);
  }

  const sprite = (tex: THREE.Texture, opacity: number) => {
    const s = new THREE.Sprite(lightOnly(new THREE.SpriteMaterial({
      map: tex, color: SIGNAL, transparent: true, opacity, depthWrite: false,
    })));
    scene.add(s);
    return s;
  };
  const coreGlow = sprite(glowTex, 0.2);
  const packetGlow = new THREE.Points(
    new THREE.BufferGeometry().setAttribute("position", new THREE.BufferAttribute(new Float32Array(16 * 3), 3)),
    lightOnly(new THREE.PointsMaterial({
      size: 0.5, map: glowTex, color: SIGNAL, transparent: true, opacity: 0.9, depthWrite: false, sizeAttenuation: true,
    })),
  );
  packetGlow.frustumCulled = false;
  scene.add(packetGlow);

  // --- load the model ---------------------------------------------------------
  const loader = new GLTFLoader();
  // The site CSP has no blob: in connect-src, so embedded textures can't be
  // fetch()ed as ImageBitmaps; load them through <img> (img-src allows blob:).
  loader.register((parser) => {
    const p = parser as unknown as { textureLoader: THREE.Loader; options: { manager: THREE.LoadingManager } };
    p.textureLoader = new THREE.TextureLoader(p.options.manager);
    return { name: "octo-img-textures" };
  });
  loader.load(model, async (gltf) => {
    if (disposed) return;
    const root = gltf.scene;
    const body = root.getObjectByName("Body");
    if (!body) {
      fail();
      return;
    }
    root.traverse((o) => {
      const m = o as THREE.Mesh;
      if (!m.isMesh) return;
      const mat = m.material as THREE.MeshStandardMaterial;
      if (mat.map) {
        mat.alphaTest = 0.4; // decals: crisp cut-out, no transparency sorting
        mat.transparent = false;
      }
      // Cycles-strength emission reads white under AgX; keep the signal blue
      if (mat.emissiveIntensity > 1) mat.emissiveIntensity = Math.min(1.6, mat.emissiveIntensity * 0.3);
    });
    root.updateMatrixWorld(true);
    for (let i = 0; i < 8; i++) {
      const r = root.getObjectByName(`ArmRoot_${i}`);
      if (!r) continue;
      const pos = r.getWorldPosition(new THREE.Vector3());
      const dir = new THREE.Vector3(0, 1, 0).applyQuaternion(r.getWorldQuaternion(new THREE.Quaternion())).normalize();
      const ex = r.userData ?? {};
      arms.push({
        rootPos: pos, rootDir: dir, len: Number(ex.length) || 2.2, r0: Number(ex.r0) || 0.15, r1: Number(ex.r1) || 0.055,
        phase: i * 0.83, group: i % 2,
        nodes: Array.from({ length: N }, () => new THREE.Vector3()),
        prev: Array.from({ length: N }, () => new THREE.Vector3()),
        rest: Array.from({ length: N }, () => new THREE.Vector3()),
        cum: new Float32Array(N),
        packets: [(i * 0.37) % 1, (i * 0.37 + 0.5) % 1],
        foot: new THREE.Vector2(), home: new THREE.Vector2(),
        step: -1, flash: 0, tip: new THREE.Vector3(),
        glow: sprite(glowTex, 0.5), ripple: sprite(ringTex, 0),
      });
    }
    bodyGroup.add(body);
    body.traverse((o) => {
      o.updateMatrix();
      o.matrixAutoUpdate = false; // the body only moves through bodyGroup
    });
    const kit = (n: string) => partsOf(root.getObjectByName(n));
    const maxDiscs = 8 * 90;
    pool("vertebra", kit("Kit_Vertebra"), maxDiscs);
    if (!lite) pool("lug", kit("Kit_Lug"), maxDiscs);
    if (!lite) pool("groove", kit("Kit_Groove"), maxDiscs);
    pool("collar", kit("Kit_Collar"), 8 * 24);
    pool("boot", kit("Kit_Boot"), 8 * 40);
    pool("ferrule", kit("Kit_Ferrule"), 8);
    // joints (octo-core.glb v2; absent in older models, where these pools stay
    // empty): a knee at each arm's highest point, a relay collar mid-span, and
    // at each tip a foot whose ball socket takes the ferrule, with three toes
    // that curl shut while it grips. Phones keep the joints that read at their
    // size (knee, foot) and skip relay and toes, like the lugs and tendons.
    pool("knee", kit("Kit_Knee"), 8);
    if (!lite) pool("relay", kit("Kit_Relay"), 8);
    const footNode = root.getObjectByName("Kit_Foot");
    pool("foot", partsOf(footNode), 8);
    if (!lite) pool("toe", kit("Kit_Toe"), 8 * 3);
    const fx = (footNode?.userData ?? {}) as { ball_y?: number; toe_mounts?: number[][] };
    ballY = Number(fx.ball_y) || 0;
    toeMounts.length = 0;
    for (const [x, y, z, yaw] of fx.toe_mounts ?? []) {
      // glTF foot space: at the mount, yawed round the wall normal (+Y), toe laid along +Z
      toeMounts.push(new THREE.Matrix4().makeTranslation(x, y, z)
        .multiply(new THREE.Matrix4().makeRotationY((yaw * Math.PI) / 180))
        .multiply(new THREE.Matrix4().makeRotationX(Math.PI / 2)));
    }
    pool("spine", [{ geometry: cyl, material: rubber }], maxDiscs);
    if (!lite) pool("tendon", [{ geometry: cyl, material: steel }], maxDiscs * 4);
    pool("packet", [{ geometry: sphere, material: packetMat }], 16);

    if (field) {
      new THREE.TextureLoader().load(field, (tex) => {
        if (disposed) return;
        tex.colorSpace = THREE.SRGBColorSpace;
        // the TouchDesigner data field, painted on the wall under the hero dock
        const mesh = new THREE.Mesh(
          new THREE.PlaneGeometry(7.7, 7.7),
          lightOnly(new THREE.MeshBasicMaterial({
            map: tex, color: new THREE.Color(0.45, 0.62, 1.0), transparent: true, opacity: 0, depthWrite: false,
          })),
        );
        renderer.compileAsync(mesh, camera, scene).catch(() => {}).then(() => {
          if (disposed) return;
          fieldMesh = mesh;
          scene.add(mesh);
        });
      });
    }
    // compile every program off the first frame, so the first live frame
    // doesn't hitch
    await renderer.compileAsync(scene, camera).catch(() => {});
    if (disposed) return;
    ready = true;
  }, undefined, () => fail());

  // --- layout: where the page anchors are ---------------------------------------
  let vw = 1, vh = 1, ppu = 1, maxScroll = 0, navH = 72;
  const dockBox = { x: 0, y: 0, w: 1 }; // page px, measured at layout
  type Anchor = { key: number; px: number; py: number; size: number };
  let anchors: Anchor[] = [];
  function layout() {
    vw = canvas.clientWidth || window.innerWidth;
    vh = canvas.clientHeight || window.innerHeight;
    renderer.setSize(vw, vh, false);
    camera.aspect = vw / vh;
    camera.updateProjectionMatrix();
    ppu = vh / (2 * CAM_Z * Math.tan(THREE.MathUtils.degToRad(FOV / 2)));
    const narrow = vw < 768;
    const sy = window.scrollY;
    maxScroll = Math.max(0, document.documentElement.scrollHeight - vh);
    navH = (document.querySelector("header, nav")?.getBoundingClientRect().height ?? 64) + 8;
    const db = dock.getBoundingClientRect();
    dockBox.x = db.left + db.width / 2;
    dockBox.y = db.top + sy + db.height / 2;
    dockBox.w = db.width;
    anchors = STOPS.flatMap((stop) => {
      const s = (narrow && stop.m) || stop;
      const el = [...document.querySelectorAll(s.at)].find((e) => e.getClientRects().length);
      if (!el) return [];
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height) return [];
      const px = s.x !== undefined ? s.x * vw : r.left + r.width * (s.ax ?? 0.5);
      const py = r.top + sy + r.height * s.ay; // page coordinates
      return [{ key: Math.min(maxScroll, Math.max(0, py - vh * 0.5)), px, py, size: s.size * vh }];
    }).sort((a, b) => a.key - b.key)
      // stops pinned to the same (maximum) scroll: the later one wins
      .filter((a, i, arr) => i === arr.length - 1 || a.key < arr[i + 1].key);
    collectText();
  }

  // Text the octopus must never sit on: every visible run of copy, in page
  // px. Copy inside an opaque card is not counted: behind a card the octopus
  // is hidden, which is where it likes to rest. Sticky copy moves with the
  // scroll, so it is re-measured every frame.
  type Box = { x0: number; y0: number; x1: number; y1: number; el: Element | null };
  let text: Box[] = [];
  const TEXT = "main h1, main h2, main h3, main h4, main p, main li, main summary, main dt, main dd, main label, main a, main button, main input, main textarea, main select, footer p, footer a, footer li";
  // opaque cards: the octopus would vanish behind them, so they are kept
  // clear as whole boxes (and the copy inside them needs no separate box)
  const OPAQUE = "main article, main form, main [role=img]:not([data-octo-dock])";
  function collectText() {
    const scroll = window.scrollY;
    text = [];
    for (const el of document.querySelectorAll(OPAQUE)) {
      const r = el.getBoundingClientRect();
      if (r.width * r.height > 0) text.push({ x0: r.left, y0: r.top + scroll, x1: r.right, y1: r.bottom + scroll, el: null });
    }
    const rng = document.createRange();
    for (const el of document.querySelectorAll(TEXT)) {
      if (el.closest(`${OPAQUE}, [data-octo-dock]`)) continue;
      // the glyphs' extent, not the block: a short line in a wide block (the
      // footer wordmark) leaves the rest of its row free
      rng.selectNodeContents(el);
      let r = rng.getBoundingClientRect();
      if (!r.width || !r.height) r = el.getBoundingClientRect();
      if (r.width * r.height < 60) continue;
      const sticky = el.closest(".lg\\:sticky");
      text.push({ x0: r.left, y0: r.top + scroll, x1: r.right, y1: r.bottom + scroll, el: sticky && getComputedStyle(sticky).position === "sticky" ? el : null });
    }
  }
  // share of a square footprint (centre x, y in page px, half-size h) covered by text
  function measureSticky(scroll: number) {
    for (const b of text) {
      if (!b.el) continue;
      const r = b.el.getBoundingClientRect();
      b.x0 = r.left;
      b.x1 = r.right;
      b.y0 = r.top + scroll;
      b.y1 = r.bottom + scroll;
    }
  }
  function textCover(x: number, y: number, h: number) {
    let area = 0;
    for (const b of text) {
      const w = Math.min(x + h, b.x1) - Math.max(x - h, b.x0);
      if (w <= 0) continue;
      const hh = Math.min(y + h, b.y1) - Math.max(y - h, b.y0);
      if (hh > 0) area += w * hh;
    }
    return area / (4 * h * h);
  }
  layout();

  // Target = where the body centre should be on the PAGE (px) and its scale.
  // It rests on each anchor (moving with the page), then crawls to the next.
  const REACH = 3.2; // model units from the body centre to a gripping tip
  const SPRING = [["x", "vx"], ["y", "vy"], ["s", "vs"]] as const;
  const target = { x: 0, y: 0, s: 1 };
  const pose = { x: 0, y: 0, s: 1, vx: 0, vy: 0, vs: 0 }; // page px, smoothed
  let docked = 1;
  function choreograph(sy: number) {
    let fx = dockBox.x;
    let fy = dockBox.y;
    let fs = dockBox.w / POSTER_FRAME / ppu;
    let fromKey = 0;
    docked = 1;
    for (const a of anchors) {
      const ts = a.size / (2 * REACH) / ppu;
      if (sy <= a.key) {
        const k = smooth(fromKey + (a.key - fromKey) * 0.25, a.key, sy);
        target.x = fx + (a.px - fx) * k;
        target.y = fy + (a.py - fy) * k;
        target.s = fs + (ts - fs) * k;
        docked *= 1 - k;
        return;
      }
      fx = a.px;
      fy = a.py;
      fs = ts;
      fromKey = a.key;
      docked = 0;
    }
    target.x = fx;
    target.y = fy;
    target.s = fs;
  }

  // Keep off the copy: try spots around the choreographed one and take the
  // cheapest (text covered, distance from the path, leaving the viewport, and
  // a little stickiness so it does not dither between two spots). In the dock
  // it stays exactly where the poster is.
  const RINGS = [0, 0.35, 0.7, 1.05, 1.45, 1.9, 2.4];
  const avoid = { x: 0, y: 0, set: false };
  let avoidTick = 0;
  function avoidText(scroll: number) {
    if (docked > 0.999) {
      avoid.set = false;
      return;
    }
    // phones re-plan every third frame; the body spring hides the gap
    if (lite && avoid.set && avoidTick++ % 3) {
      const k = 1 - docked;
      target.x += (avoid.x - target.x) * k;
      target.y += (avoid.y - target.y) * k;
      return;
    }
    measureSticky(scroll);
    const unit = target.s * ppu;
    const hb = 1.7 * unit; // the body and shoulders
    const h = 3.6 * unit; // everything the arms reach at rest
    const cost = (x: number, y: number) => {
      // the whole footprint should sit on screen and below the nav
      const yv = y - scroll;
      const off = Math.max(0, h * 0.8 - x, x - (vw - h * 0.8)) + Math.max(0, navH + h * 0.8 - yv, yv - (vh - h * 0.8));
      // ...and at least half the body must stay in view: on a phone, where copy
      // spans the width, it peeks in from the margin instead of sitting on a
      // paragraph or hiding off screen
      const out = Math.max(0, -x - 0.6 * hb, x - vw - 0.6 * hb) + Math.max(0, navH - yv, yv - vh);
      return textCover(x, y, hb) * 30 + textCover(x, y, h) * 14 + Math.hypot(x - target.x, y - target.y) / vw * 1.4 + (off / h) * 3 + (out / hb) * 60;
    };
    let bx = target.x, by = target.y, best = cost(bx, by);
    for (const r of RINGS) {
      if (!r) continue;
      const spokes = lite ? 10 : 16;
      for (let k = 0; k < spokes; k++) {
        const a = (k / spokes) * Math.PI * 2;
        const x = target.x + Math.cos(a) * r * h;
        const y = target.y + Math.sin(a) * r * h;
        const c = cost(x, y);
        if (c < best) {
          best = c;
          bx = x;
          by = y;
        }
      }
    }
    if (avoid.set && cost(avoid.x, avoid.y) < best * 1.15 + 0.02) {
      bx = avoid.x;
      by = avoid.y;
    }
    avoid.x = bx;
    avoid.y = by;
    avoid.set = true;
    const k = 1 - docked; // blend out of the dock
    target.x += (bx - target.x) * k;
    target.y += (by - target.y) * k;
  }

  // --- simulation ---------------------------------------------------------------
  const qIdle = new THREE.Quaternion();
  const qLean = new THREE.Quaternion();
  const qSpin = new THREE.Quaternion();
  const qView = new THREE.Quaternion();
  const ray = new THREE.Vector3();
  const minusZ = new THREE.Vector3(0, 0, -1);
  const v3 = new THREE.Vector3();
  const v4 = new THREE.Vector3();
  const v5 = new THREE.Vector3();
  const v2 = new THREE.Vector2();
  const tmpM = new THREE.Matrix4();
  const bodyM = new THREE.Matrix4();
  let spin = 0;
  let first = true;
  let t = 0;
  let sy = window.scrollY;
  const debug: Record<string, unknown> | null = location.search.includes("octo-debug") ? {} : null;
  if (debug) (window as unknown as { __octo: unknown }).__octo = debug;

  // page px <-> world on the wall plane (z = 0)
  const toWorld = (px: number, py: number, out: THREE.Vector3) =>
    out.set((px - vw / 2) / ppu, -(py - sy - vh / 2) / ppu, 0);
  const toPage = (w: THREE.Vector3, out: THREE.Vector2) => out.set(w.x * ppu + vw / 2, -w.y * ppu + vh / 2 + sy);

  const restQ = new THREE.Quaternion();
  const axisTmp = new THREE.Vector3();
  function restPose(a: Arm, time: number) {
    // integrate a heading from the root: curl around the body axis, dip
    // towards the wall, undulate, curl the tip (same as the Blender poster)
    const ds = a.len / (N - 1);
    const p = v3.copy(a.rootPos);
    const dir = v4.copy(a.rootDir);
    a.rest[0].copy(p);
    for (let k = 1; k < N; k++) {
      const u = k / (N - 1);
      const w = Math.sin(u * 7.5 - time * 1.9 + a.phase);
      const w2 = Math.cos(u * 5.2 - time * 1.3 + a.phase * 1.7);
      const yaw = (1.15 / a.len + 0.8 * w * (0.35 + u) / a.len + (2.4 * u ** 3) / a.len) * ds; // tips curl
      let pitch = 0.35 / a.len * ds + 0.4 * w2 * u * ds / a.len;
      if (u > 0.78) pitch -= 1.6 * ((u - 0.78) / 0.22) * ds / a.len;
      restQ.setFromAxisAngle(up, yaw);
      dir.applyQuaternion(restQ);
      axisTmp.crossVectors(dir, up).normalize();
      restQ.setFromAxisAngle(axisTmp, -pitch);
      dir.applyQuaternion(restQ).normalize();
      p.addScaledVector(dir, ds);
      a.rest[k].copy(p);
    }
  }

  // arm dressing -----------------------------------------------------------------
  const frame = new THREE.Matrix4();
  const basis = new THREE.Matrix4();
  const pT = new THREE.Vector3();
  const tT = new THREE.Vector3();
  const nT = new THREE.Vector3();
  const sT = new THREE.Vector3();
  const prevT = new THREE.Vector3();
  const segDir = new THREE.Vector3();
  const qSeg = new THREE.Quaternion();
  const sc = new THREE.Vector3();
  const packetArr = packetGlow.geometry.getAttribute("position") as THREE.BufferAttribute;
  let ballY = 0; // Kit_Foot ball centre above the wall, in tip radii (r1)
  const toeMounts: THREE.Matrix4[] = [];
  const footM = new THREE.Matrix4();
  const toeM = new THREE.Matrix4();
  const curlM = new THREE.Matrix4();
  const footUp = new THREE.Vector3();
  const footOut = new THREE.Vector3();
  let packetN = 0;

  function orient(out: THREE.Matrix4, p: THREE.Vector3, tan: THREE.Vector3, nrm: THREE.Vector3, s: number) {
    // template X -> n x t, Y -> tangent, Z -> -n: a proper rotation, so
    // instanced parts keep their winding
    sT.crossVectors(nrm, tan);
    basis.makeBasis(sT, tan, v5.copy(nrm).negate());
    return out.copy(basis).scale(sc.set(s, s, s)).setPosition(p);
  }

  // Catmull-Rom through the arm's nodes, addressed by arc length (chord
  // table): O(1) per lookup after an O(N) table, no allocations
  function along(a: Arm, x: number, outP: THREE.Vector3, outT: THREE.Vector3 | null) {
    const n = a.nodes;
    const c = a.cum;
    const d = Math.min(c[N - 1], Math.max(0, x));
    let k = 0;
    while (k < N - 2 && c[k + 1] < d) k++;
    const t = (d - c[k]) / Math.max(c[k + 1] - c[k], 1e-9);
    const p0 = n[Math.max(0, k - 1)], p1 = n[k], p2 = n[k + 1], p3 = n[Math.min(N - 1, k + 2)];
    const t2 = t * t, t3 = t2 * t;
    for (const ax of ["x", "y", "z"] as const) {
      const a0 = p0[ax], a1 = p1[ax], a2 = p2[ax], a3 = p3[ax];
      const b = -a0 + a2, c2 = 2 * a0 - 5 * a1 + 4 * a2 - a3, c3 = -a0 + 3 * a1 - 3 * a2 + a3;
      outP[ax] = 0.5 * (2 * a1 + b * t + c2 * t2 + c3 * t3);
      if (outT) outT[ax] = 0.5 * (b + 2 * c2 * t + 3 * c3 * t2);
    }
    if (outT) outT.normalize();
    return outP;
  }

  function dressArm(a: Arm, s: number) {
    a.cum[0] = 0;
    for (let k = 1; k < N; k++) a.cum[k] = a.cum[k - 1] + a.nodes[k].distanceTo(a.nodes[k - 1]);
    const L = a.cum[N - 1];
    const rad = (x: number) => (a.r0 + (a.r1 - a.r0) * Math.pow(clamp01(x / L), 0.8)) * s;
    let x = 0;
    along(a, 0, pT, tT);
    nT.copy(zAxis); // the camera side of the arm carries the LEDs and packets
    nT.addScaledVector(tT, -nT.dot(tT)).normalize();
    const station = (at: number) => {
      along(a, at, pT, tT);
      nT.addScaledVector(tT, -nT.dot(tT)).normalize();
    };
    // a stretched arm gets sparser parts, never more: pools can't overflow
    const spread = Math.max(1, L / (1.6 * a.len * s)) * (lite ? 1.35 : 1);
    // the knee sits where the arm stands highest off the wall; the relay at 55%
    let hi = 1;
    for (let k = 2; k < N - 1; k++) if (a.nodes[k].z > a.nodes[hi].z) hi = k;
    const kneeAt = a.cum[hi];
    const relayAt = 0.55 * L;
    let knee = !pools.get("knee")?.length;
    let relay = !pools.get("relay")?.length;
    let n = 0;
    prevT.copy(pT);
    while (x < 0.12 * L) {
      station(x);
      place("boot", orient(frame, pT, tT, nT, rad(x) * 1.04));
      x += rad(x) * 0.2 * spread;
    }
    while (x < L - 2.6 * rad(L)) {
      station(x);
      const r = rad(x);
      orient(frame, pT, tT, nT, r);
      if (!relay && x + 0.86 * r >= relayAt) {
        // the relay collar takes three disc slots; it is centred in them
        station(x + 0.86 * r);
        place("relay", orient(frame, pT, tT, nT, rad(x + 0.86 * r)));
        relay = true;
        x += 1.725 * r * spread;
      } else if (!knee && x + 0.39 * r >= kneeAt) {
        place("knee", frame);
        knee = true;
        x += r * 0.78 * spread;
      } else if (n % 6 === 5) {
        place("collar", frame);
        x += r * 0.78 * spread;
      } else {
        place("vertebra", frame);
        place("lug", frame);
        if (n % 3 === 1) place("groove", frame);
        x += r * 0.575 * spread;
      }
      // spine and the four tendons span from the previous station to this one
      segDir.subVectors(pT, prevT);
      const segLen = segDir.length();
      if (segLen > 1e-5 && n > 0) {
        segDir.divideScalar(segLen);
        qSeg.setFromUnitVectors(up, segDir);
        v4.addVectors(pT, prevT).multiplyScalar(0.5);
        place("spine", tmpM.compose(v4, qSeg, sc.set(r * 0.62, segLen, r * 0.62)));
        if (pools.has("tendon")) {
          sT.crossVectors(nT, tT);
          for (let k = 0; k < 4; k++) {
            const ang = Math.PI / 4 + (k * Math.PI) / 2;
            v3.copy(sT).multiplyScalar(Math.cos(ang)).addScaledVector(nT, Math.sin(ang)).multiplyScalar(r * 1.1).add(v4);
            place("tendon", tmpM.compose(v3, qSeg, sc.set(r * 0.09, segLen, r * 0.09)));
          }
        }
      }
      prevT.copy(pT);
      n++;
    }
    // close the spine and tendons to the ferrule's back face (it spans
    // -2.2..+0.25 tip radii along the arm): no bare gap before the coupler
    const rL = rad(L);
    station(L - 2.44 * rL);
    segDir.subVectors(pT, prevT);
    const tail = segDir.length();
    if (tail > 1e-5 && n > 0) {
      segDir.divideScalar(tail);
      qSeg.setFromUnitVectors(up, segDir);
      v4.addVectors(pT, prevT).multiplyScalar(0.5);
      place("spine", tmpM.compose(v4, qSeg, sc.set(rL * 0.62, tail, rL * 0.62)));
      if (pools.has("tendon")) {
        sT.crossVectors(nT, tT);
        for (let k = 0; k < 4; k++) {
          const ang = Math.PI / 4 + (k * Math.PI) / 2;
          v3.copy(sT).multiplyScalar(Math.cos(ang)).addScaledVector(nT, Math.sin(ang)).multiplyScalar(rL * 1.1).add(v4);
          place("tendon", tmpM.compose(v3, qSeg, sc.set(rL * 0.09, tail, rL * 0.09)));
        }
      }
    }
    station(L - 0.24 * rL);
    place("ferrule", orient(frame, pT, tT, nT, rL));
    if (ballY) {
      // the foot hangs under the ball the ferrule is seated in: sole on the
      // wall while gripping, lifted with the tip in a swing; +Z faces away
      // from the body, toes curl shut while it grips and open mid-swing
      const tipN = a.nodes[N - 1];
      footUp.set(0, 0, 1);
      footOut.set(tipN.x - a.nodes[0].x, tipN.y - a.nodes[0].y, 0);
      if (footOut.lengthSq() < 1e-10) footOut.set(1, 0, 0); // tip straight under the root
      footOut.normalize();
      v3.copy(tipN).addScaledVector(footUp, -ballY * rL);
      orient(footM, v3, footUp, footOut.negate(), rL);
      place("foot", footM);
      if (pools.has("toe")) {
        const curl = -0.35 * (a.step < 0 ? 1 : 1 - Math.sin(Math.PI * a.step));
        curlM.makeRotationX(curl);
        for (const m of toeMounts) place("toe", toeM.multiplyMatrices(footM, m).multiply(curlM));
      }
    }
    a.tip.copy(pT).addScaledVector(tT, 0.3 * rad(L));
    // packets ride from the gripping tip (the source) to the core
    for (const u of a.packets) {
      along(a, (1 - u) * L, v4, null);
      v4.z += rad(L) * 1.6;
      if (packetN < packetArr.count) packetArr.setXYZ(packetN++, v4.x, v4.y, v4.z);
      place("packet", tmpM.makeScale(rad(L) * 0.55, rad(L) * 0.55, rad(L) * 0.55).setPosition(v4));
    }
  }

  // one arm: rest shape warped so its tip lands on the grip, then a verlet
  // relaxation for follow-through, then FABRIK so the arm keeps its length
  // at any speed. A grip never pulls the arm past 1.5x its length.
  const before = Array.from({ length: N }, () => new THREE.Vector3());
  function reach(a: Arm, dt: number, s: number, lift: number) {
    const pinTip = true; // a tip is always on the wall, or stepping to it
    const root = a.rest[0];
    const seat = ballY * a.r1 * s; // the ferrule seats in the foot's ball, not on the wall
    toWorld(a.foot.x, a.foot.y, v5).setZ(lift + seat);
    const maxR = a.len * s * 1.5;
    const gx = v5.x - root.x;
    const gy = v5.y - root.y;
    const gd = Math.hypot(gx, gy);
    if (gd > maxR) {
      v5.x = root.x + (gx / gd) * maxR;
      v5.y = root.y + (gy / gd) * maxR;
      // (the gait keeps every planted foot inside this radius; see gaitReach
      // below, so this only guards an out-of-range swing and never moves a
      // planted foot)
      v5.setZ(lift + seat);
    }
    const tip = a.rest[N - 1];
    const arch = 0.12 * a.len * s;
    let restLen = 0;
    for (let i = 0; i < N; i++) {
      const u = i / (N - 1);
      const w = u ** 1.5;
      a.rest[i].x += (v5.x - tip.x) * w;
      a.rest[i].y += (v5.y - tip.y) * w;
      a.rest[i].z += (v5.z - tip.z) * w + Math.sin(Math.PI * u) * arch;
      if (i) restLen += a.rest[i].distanceTo(a.rest[i - 1]);
    }
    const seg = Math.min(restLen, a.len * s * 1.6) / (N - 1);
    const n = a.nodes;
    n[0].copy(a.rest[0]);
    if (pinTip) n[N - 1].copy(a.rest[N - 1]);
    const damp = Math.pow(0.88, dt * 60);
    const last = pinTip ? N - 1 : N;
    for (let i = 1; i < last; i++) {
      const u = i / (N - 1);
      // 1/s^2: gripping arms are loosest mid-arm; gliding arms loosest at the tip
      const stiff = pinTip ? 520 * (1 - 0.6 * Math.sin(Math.PI * u)) : 420 * (1 - 0.86 * u);
      v3.subVectors(n[i], a.prev[i]).multiplyScalar(damp);
      v4.subVectors(a.rest[i], n[i]).multiplyScalar(Math.min(0.5, stiff * dt * dt));
      a.prev[i].copy(n[i]);
      n[i].add(v3).add(v4);
    }
    for (let i = 0; i < N; i++) before[i].copy(n[i]);
    const tipAt = v4.copy(pinTip ? a.rest[N - 1] : n[N - 1]);
    for (let it = 0; it < 2; it++) {
      n[N - 1].copy(tipAt);
      for (let i = N - 2; i >= 0; i--) n[i].sub(n[i + 1]).setLength(seg).add(n[i + 1]);
      n[0].copy(a.rest[0]);
      for (let i = 1; i < N; i++) n[i].sub(n[i - 1]).setLength(seg).add(n[i - 1]);
    }
    if (pinTip) n[N - 1].copy(a.rest[N - 1]);
    // the constraint moves positions only: carry it into prev, no new velocity
    for (let i = 1; i < last; i++) a.prev[i].add(v3.subVectors(n[i], before[i]));
    a.prev[0].copy(n[0]);
    if (pinTip) a.prev[N - 1].copy(n[N - 1]);
  }

  // --- frame loop -------------------------------------------------------------
  let last = performance.now();
  let frames = 0;
  let slow = 0;
  let clean = 0;
  let ceil = maxDpr;
  let lastActive = performance.now();
  let fpsCap = 60;
  let lastSy = window.scrollY;
  let sleeping = false;
  const gait = createSpiderGait({ legs: 8 });
  const homes: THREE.Vector2[] = [];
  const leash = { x: 0, y: 0 };
  function loop(now: number) {
    if (lite && now - last < 1000 / fpsCap - 2) return; // 60 fps is plenty on phones
    const cpu0 = performance.now();
    const dt = Math.min(0.05, (now - last) / 1000) || 1 / 60;
    last = now;
    t += dt;
    if (!ready) return;

    // adaptive resolution, in 2 s windows: step down when a window is slow,
    // back up after ten clean windows, never above the last level that hitched
    frames++;
    if (dt > 0.024) slow++;
    if (frames >= 120) {
      const was = dpr;
      if (slow > 30 && dpr > 1) {
        ceil = dpr;
        dpr = Math.max(1, dpr - 0.25);
        clean = 0;
      } else if (slow === 0 && ++clean >= 10 && dpr < ceil) {
        dpr = Math.min(ceil, dpr + 0.25);
        clean = 0;
      } else if (slow) clean = 0;
      if (dpr !== was) {
        renderer.setPixelRatio(dpr);
        renderer.setSize(vw, vh, false);
      }
      // already at the lowest resolution and still mostly slow: an even 30
      if (lite && dpr === 1 && slow > 90) fpsCap = 30;
      frames = slow = 0;
    }

    sy = window.scrollY;
    if (sy !== lastSy) {
      lastSy = sy;
      lastActive = now;
    }
    choreograph(Math.min(maxScroll, Math.max(0, sy)));
    avoidText(sy);
    // It only ever crawls. A leash walks towards the target at the pace the
    // legs can carry the body (about 3.3 reaches a second); across copy, far
    // behind or out of view the legs hurry, up to 1.6x (the gait keeps every
    // planted foot within reach up to ~10 units/s), but it never leaves the
    // wall. The spring then follows the leash.
    const unitT = target.s * ppu;
    const exposed = docked > 0.999 ? 0 : Math.min(1, textCover(leash.x, leash.y, REACH * unitT * 0.9) * 3);
    if (first) {
      leash.x = target.x;
      leash.y = target.y;
    }
    const lx = target.x - leash.x;
    const ly = target.y - leash.y;
    const ld = Math.hypot(lx, ly);
    const bodyV = pose.y - sy;
    const outOfView = bodyV < navH || bodyV > vh || pose.x < 0 || pose.x > vw;
    const hurry = Math.min(1.6, 1 + exposed + Math.max(0, ld - 3 * unitT) / (6 * unitT) + (outOfView ? 0.5 : 0));
    const vmax = 6 * unitT * hurry;
    if (ld <= vmax * dt) {
      leash.x = target.x;
      leash.y = target.y;
    } else {
      leash.x += (lx / ld) * vmax * dt;
      leash.y += (ly / ld) * vmax * dt;
    }
    const w = 9;
    for (const [k, vk] of SPRING) {
      const goal = k === "x" ? leash.x : k === "y" ? leash.y : target.s;
      if (first) {
        pose[k] = goal;
        pose[vk] = 0;
        continue;
      }
      pose[vk] += (w * w * (goal - pose[k]) - 2 * w * pose[vk]) * dt;
      pose[k] += pose[vk] * dt;
    }
    // Out of sight it never trails far behind: a body more than an arm's length
    // outside the view is carried along the view's edge with its feet reset, so
    // a fast scroll on a long (phone) page still finds it crawling back in.
    // Nobody sees the carry; every visible frame is a real crawl.
    let snap = first;
    if (docked === 0) {
      const m = 5 * unitT;
      const cx = Math.min(vw + m, Math.max(-m, pose.x));
      const cy = Math.min(sy + vh + m, Math.max(sy + navH - m, pose.y));
      if (cx !== pose.x || cy !== pose.y) {
        pose.x = leash.x = cx;
        pose.y = leash.y = cy;
        snap = true;
      }
    }
    const s = pose.s;
    const crawl = Math.hypot(pose.vx, pose.vy); // page px/s: how fast it crawls
    const moving = smooth(20, 700, crawl);

    // orientation: back to the viewer (80 deg, as in the poster), measured
    // from the ray that reaches it; leans into the crawl; turns slowly
    const screenX = pose.x;
    const screenY = pose.y - sy;
    ray.set((screenX - vw / 2) / ppu, -(screenY - vh / 2) / ppu, -CAM_Z).normalize();
    qView.setFromUnitVectors(minusZ, ray);
    spin += dt * (0.035 + moving * 0.06);
    qSpin.setFromAxisAngle(up, first ? 0 : spin);
    qIdle.setFromAxisAngle(v3.set(1, 0, 0), POSTER_ELEV).premultiply(qView).multiply(qSpin);
    v3.set(-pose.vy, -pose.vx, 0); // lean axis: perpendicular to the motion
    const leanAmt = Math.min(0.2, crawl / 3500);
    if (leanAmt > 1e-4) qLean.setFromAxisAngle(v3.normalize(), -leanAmt).multiply(qIdle);
    else qLean.copy(qIdle);
    if (first) octo.quaternion.copy(qLean);
    else octo.quaternion.slerp(qLean, 1 - Math.exp(-dt * 6));

    // body height above the wall bobs with each step
    let inAir = 0;
    for (const a of arms) if (a.step >= 0) inAir = Math.max(inAir, Math.sin(Math.PI * a.step));
    octo.scale.setScalar(s);
    const wx = (screenX - vw / 2) / ppu;
    const wy = -(screenY - vh / 2) / ppu;
    v3.copy(POSTER_AIM).applyQuaternion(octo.quaternion).multiplyScalar(s * docked);
    octo.position.set(wx - v3.x, wy - v3.y, (WALL + 0.06 * inAir) * s);
    octo.updateMatrixWorld(true);
    bodyM.copy(bodyGroup.matrixWorld);

    // rest shapes in world space, and each arm's home grip on the page
    for (const a of arms) {
      restPose(a, t);
      for (let i = 0; i < N; i++) a.rest[i].applyMatrix4(bodyM);
      toPage(a.rest[N - 1], a.home);
    }
    if (homes.length !== arms.length) homes.splice(0, homes.length, ...arms.map((a) => a.home));

    // gait (spider-gait.ts): an alternating tetrapod on the ring of arms,
    // Cruse's coordination rules, minimum-jerk swings. Feet are in page px,
    // so a gripping tip moves exactly with the content under it.
    // sized to the arm: a foot may sit up to ~1.5 model units from its home
    // before it must step (beyond that the arm would pass 1.5x its length)
    const unitPx = s * ppu;
    if (snap) gait.reset(homes);
    // The gait's reach is foot-to-home; the arm's is root-to-foot (1.5x its
    // length). Budget the gait so home + reach stays inside every arm's range
    // (triangle inequality): a planted foot is then never clamped (no slip)
    // and a swing never snaps out of a clamp (no jump), even while the body
    // shrinks out of the dock.
    let gaitReach = 1.5 * unitPx;
    for (const a of arms) {
      toPage(a.rest[0], v2);
      gaitReach = Math.min(gaitReach, a.len * s * 1.5 * ppu - v2.distanceTo(a.home));
    }
    gaitReach = Math.max(gaitReach, 0.9 * unitPx);
    const { feet } = gait.update(dt, {
      homes,
      velocity: v2.set(pose.vx, pose.vy), // the spring's own velocity: smooth, no dt spikes
      stepDist: Math.min(0.6 * unitPx, 0.45 * gaitReach),
      reach: gaitReach,
    });
    for (let i = 0; i < arms.length; i++) {
      const a = arms[i];
      const f = feet[i];
      if (a.step >= 0 && f.planted) a.flash = 1; // touchdown
      a.step = f.planted ? -1 : f.phase;
      a.foot.set(f.x, f.y);
      a.flash = Math.max(0, a.flash - dt * 2.2);
      if (snap) {
        for (let k = 0; k < N; k++) {
          a.nodes[k].copy(a.rest[k]);
          a.prev[k].copy(a.rest[k]);
        }
      }
      reach(a, dt, s, f.lift * 0.3 * a.len * s);
    }

    // packets: data runs from each gripping tip into the core
    for (const a of arms) {
      for (let j = 0; j < a.packets.length; j++) {
        a.packets[j] += dt * 0.24;
        if (a.packets[j] >= 1) {
          a.packets[j] -= 1;
          coreFlash = 1;
        }
      }
    }
    coreFlash = Math.max(0, coreFlash - dt * 2.2);

    counts.clear();
    packetN = 0;
    for (const a of arms) dressArm(a, s);
    for (const [name, meshes] of pools) {
      const c = counts.get(name) ?? 0;
      for (const m of meshes) {
        m.count = c;
        if (c > 0) {
          m.instanceMatrix.clearUpdateRanges();
          m.instanceMatrix.addUpdateRange(0, c * 16);
          m.instanceMatrix.needsUpdate = true;
        }
      }
    }
    for (let i = packetN; i < packetArr.count; i++) packetArr.setXYZ(i, 0, 0, -1000);
    packetArr.needsUpdate = true;
    (packetGlow.material as THREE.PointsMaterial).size = 0.55 * s;

    // grips glow, and flash a ripple on the wall as they plant
    for (const a of arms) {
      const planted = a.step < 0 ? 1 : 0.35;
      a.glow.position.copy(a.tip);
      a.glow.scale.setScalar((0.55 + a.flash * 0.5) * s);
      a.glow.material.opacity = 0.45 * planted + a.flash * 0.4;
      toWorld(a.foot.x, a.foot.y, a.ripple.position);
      a.ripple.scale.setScalar((0.25 + (1 - a.flash) * 0.9) * s);
      a.ripple.material.opacity = a.flash * 0.55;
    }
    v3.set(0, 0.5, 0).applyMatrix4(bodyM);
    coreGlow.position.copy(v3);
    coreGlow.scale.setScalar((1.5 + coreFlash * 0.8) * s);
    coreGlow.material.opacity = 0.16 + coreFlash * 0.34;

    // the data field is painted on the wall under the dock; it fades as the
    // octopus leaves the hero
    if (fieldMesh) {
      const m = fieldMesh.material as THREE.MeshBasicMaterial;
      m.opacity = 0.45 * docked;
      fieldMesh.visible = m.opacity > 0.01;
      fieldMesh.position.set(wx, wy, -0.02);
      fieldMesh.scale.setScalar(s);
      fieldMesh.rotation.z -= dt * 0.04;
    }

    renderer.render(scene, camera);
    if (crawl > 5 || arms.some((a) => a.step >= 0) || first) lastActive = now;
    if (now - lastActive > 4500 && !debug) {
      sleeping = true;
      renderer.setAnimationLoop(null);
    }
    if (debug) {
      // ?octo-debug only: a per-frame snapshot for the crawl probes
      Object.assign(debug, {
        t, cpu: +(performance.now() - cpu0).toFixed(2), crawl, docked, inAir, s, scrollY: sy, leaping: false, snap, gaitReach: gaitReach / unitPx,
        body: { x: screenX, y: screenY, docX: pose.x, docY: pose.y, rot: spin },
        tips: arms.map((a, i) => ({ i, group: a.group, planted: a.step < 0, x: a.foot.x, y: a.foot.y - sy, docX: a.foot.x, docY: a.foot.y })),
      });
    }
    if (first) {
      first = false;
      onReady();
    }
  }

  const run = () => renderer.setAnimationLoop(document.hidden ? null : loop);
  const wake = () => {
    if (!sleeping || disposed) return;
    sleeping = false;
    last = lastActive = performance.now();
    run();
  };
  const onVisibility = () => {
    last = performance.now();
    if (!sleeping) run();
  };
  // A lost context blanks the canvas: bring the poster back; on restore,
  // rebuild the environment (a render-target texture is not re-uploaded)
  const onLost = () => {
    delete dock.dataset.live;
    canvas.style.opacity = "0";
  };
  const onRestored = () => {
    const old = env;
    env = bakeEnv();
    scene.environment = env;
    old.dispose();
    first = true; // onReady runs again after the first restored frame
    wake();
  };
  document.addEventListener("visibilitychange", onVisibility);
  window.addEventListener("scroll", wake, { passive: true });
  canvas.addEventListener("webglcontextlost", onLost);
  canvas.addEventListener("webglcontextrestored", onRestored);
  const ro2 = new ResizeObserver(() => {
    layout();
    wake();
  });
  ro2.observe(canvas);
  ro2.observe(document.body);
  run();

  function dispose() {
    if (disposed) return;
    disposed = true;
    renderer.setAnimationLoop(null);
    document.removeEventListener("visibilitychange", onVisibility);
    window.removeEventListener("scroll", wake);
    canvas.removeEventListener("webglcontextlost", onLost);
    canvas.removeEventListener("webglcontextrestored", onRestored);
    ro2.disconnect();
    scene.traverse((o) => {
      if ((o as THREE.InstancedMesh).isInstancedMesh) (o as THREE.InstancedMesh).dispose();
      const m = o as THREE.Mesh;
      m.geometry?.dispose?.();
      const mat = m.material as THREE.Material | THREE.Material[] | undefined;
      (Array.isArray(mat) ? mat : mat ? [mat] : []).forEach((x) => {
        (x as THREE.MeshStandardMaterial).map?.dispose();
        x.dispose();
      });
    });
    env.dispose();
    pmrem.dispose();
    glowTex.dispose();
    ringTex.dispose();
    renderer.dispose();
  }
  // a model that fails to load: free the GPU, the poster stays
  function fail() {
    dispose();
    renderer.forceContextLoss();
  }
  return dispose;
}
