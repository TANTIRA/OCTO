"use client";

import { animate, createScope, onScroll, type Scope } from "animejs";
import { useEffect, useRef } from "react";

export const EASE = "outExpo";

/**
 * Runs `setup` inside an anime.js scope rooted at the returned ref, so every
 * selector resolves inside the component and every animation, scroll observer
 * and inline style is reverted on unmount. `reduce` mirrors
 * prefers-reduced-motion and re-runs setup when the preference flips.
 */
export function useAnime<T extends HTMLElement | SVGElement>(
  setup: (scope: Scope, reduce: boolean) => void,
) {
  const root = useRef<T>(null);
  const setupRef = useRef(setup);
  setupRef.current = setup;

  useEffect(() => {
    const scope = createScope({
      root,
      mediaQueries: { reduce: "(prefers-reduced-motion: reduce)" },
    }).add((self) => setupRef.current(self!, Boolean(self?.matches.reduce)));
    return () => scope.revert();
  }, []);

  return root;
}

/**
 * Fades each `[data-anim]` element under `root` up as it scrolls into view.
 * One observer per element; items sharing a row cascade left to right.
 */
export function revealOnScroll(root: ParentNode) {
  root.querySelectorAll<HTMLElement>("[data-anim]").forEach((el) => {
    animate(el, {
      opacity: [0, 1],
      translateY: [28, 0],
      duration: 1100,
      ease: EASE,
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
