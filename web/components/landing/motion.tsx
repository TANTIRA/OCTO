"use client";

import { animate, createScope, onScroll, spring as physics, type Scope } from "animejs";
import { useEffect, useRef } from "react";

// Closed-form springs (motion blueprint presets). A spring sets its own
// duration from its physics, so callers pass no duration. Fresh per use.
export const spring = {
  snappy: () => physics({ stiffness: 260, damping: 24 }), // UI and labels: tiny overshoot, settles in 0.88 s
  standard: () => physics({ stiffness: 170, damping: 26 }), // type and cards: critically damped, 0.96 s
  playful: () => physics({ stiffness: 130, damping: 18 }), // hero objects only, 0.98 s
  long: () => physics({ stiffness: 40, damping: 12.6 }), // draws and travel: critically damped, 1.78 s
};

/**
 * Runs `setup` inside an anime.js scope rooted at the returned ref, so every
 * selector resolves inside the component and every animation, scroll observer
 * and inline style is reverted on unmount. `reduce` mirrors
 * prefers-reduced-motion; setup re-runs when it or any extra `queries` flip.
 */
export function useAnime<T extends HTMLElement | SVGElement>(
  setup: (scope: Scope, reduce: boolean) => void,
  queries?: Record<string, string>,
) {
  const root = useRef<T>(null);
  const setupRef = useRef(setup);
  setupRef.current = setup;

  useEffect(() => {
    const scope = createScope({
      root,
      mediaQueries: { reduce: "(prefers-reduced-motion: reduce)", ...queries },
    }).add((self) => setupRef.current(self!, Boolean(self?.matches.reduce)));
    return () => scope.revert();
  }, []);

  return root;
}

/**
 * Lifts each `[data-anim]` element under `root` into place as it scrolls into
 * view. One observer per element; items sharing a row cascade left to right.
 */
export function revealOnScroll(root: ParentNode) {
  root.querySelectorAll<HTMLElement>("[data-anim]").forEach((el) => {
    animate(el, {
      opacity: [0, 1],
      translateY: [28, 0],
      ease: spring.standard(),
      delay: Math.round((el.getBoundingClientRect().left / window.innerWidth) * 240),
      autoplay: onScroll({ enter: "92% start" }),
    });
  });
}

/**
 * Client island that reveals its `[data-anim]` descendants on scroll, so the
 * sections around it can stay server components. Do not nest.
 */
export function Reveal({
  className,
  children,
}: {
  className?: string;
  children: React.ReactNode;
}) {
  const root = useAnime<HTMLDivElement>((scope, reduce) => {
    if (!reduce) revealOnScroll(scope.root as ParentNode);
  });
  return (
    <div ref={root} className={className}>
      {children}
    </div>
  );
}
