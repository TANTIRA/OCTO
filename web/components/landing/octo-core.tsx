"use client";

import { useEffect, useRef } from "react";

// Mounts the live ledger core (octo-core-scene.ts) on a fixed canvas above the
// page. The hero's [data-octo-dock] keeps a Cycles poster of the same pose, so
// first paint has the mech with no wait; the canvas takes over once its first
// frame is drawn and the hero's reveal of the poster has finished. Reduced
// motion (at load or switched on later), no WebGL, or a failed load: the
// poster simply stays.
export function OctoCore() {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current;
    const dock = document.querySelector<HTMLElement>("[data-octo-dock]");
    if (!canvas || !dock) return;
    const mounted = performance.now();
    const mq = matchMedia("(prefers-reduced-motion: reduce)");
    let stop: (() => void) | undefined;
    let idle: number | undefined;
    let wait: number | undefined;
    let gen = 0;

    const show = () => {
      canvas.style.opacity = "1";
      dock.dataset.live = "";
    };
    // don't cut the hero's poster reveal short (hero.tsx sets data-intro)
    const showAfterIntro = () => {
      if (dock.dataset.intro || performance.now() - mounted > 2500) show();
      else wait = window.setTimeout(showAfterIntro, 100);
    };
    const halt = () => {
      gen++;
      if (idle !== undefined) (window.cancelIdleCallback ?? window.clearTimeout)(idle);
      window.clearTimeout(wait);
      stop?.();
      stop = undefined;
      canvas.style.opacity = "";
      delete dock.dataset.live;
    };
    const schedule = () => {
      const my = ++gen;
      const start = () =>
        import("./octo-core-scene")
          .then(({ startOcto }) => {
            if (my !== gen) return;
            stop = startOcto({
              canvas,
              dock,
              model: "/models/octo-core.glb",
              field: "/models/octo-field.webp",
              onReady: showAfterIntro,
            });
          })
          .catch((err) => {
            // WebGL unavailable or the chunk failed: the poster stays
            console.warn("octo-core: live 3D unavailable", err);
          });
      idle = window.requestIdleCallback
        ? window.requestIdleCallback(start, { timeout: 800 })
        : window.setTimeout(start, 200);
    };
    const onChange = () => (mq.matches ? halt() : schedule());

    if (!mq.matches) schedule();
    mq.addEventListener("change", onChange);
    return () => {
      mq.removeEventListener("change", onChange);
      halt();
    };
  }, []);

  return (
    <canvas
      ref={ref}
      aria-hidden
      className="pointer-events-none fixed inset-0 z-30 h-lvh w-screen opacity-0 transition-opacity duration-700"
    />
  );
}
