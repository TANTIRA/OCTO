"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Tracks whether a scrollable element has hidden content above/below the
 * fold so callers can render fade affordances on the clipped edges.
 */
export function useScrollFade<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [edges, setEdges] = useState({ start: false, end: false });

  const update = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const { scrollTop, scrollHeight, clientHeight } = el;
    const start = scrollTop > 1;
    const end = Math.ceil(scrollTop + clientHeight) < scrollHeight - 1;
    setEdges((prev) => (prev.start === start && prev.end === end ? prev : { start, end }));
  }, []);

  useEffect(() => {
    update();
    const el = ref.current;
    const view = el?.ownerDocument.defaultView;
    if (!el || !view?.ResizeObserver) return;
    const observer = new view.ResizeObserver(update);
    observer.observe(el);
    // Content that loads or is swapped after mount changes scrollHeight
    // without resizing the scroller, so watch the direct children as well.
    for (const child of Array.from(el.children)) observer.observe(child);
    const children = new view.MutationObserver((records) => {
      for (const record of records) {
        record.removedNodes.forEach((n) => n instanceof view.Element && observer.unobserve(n));
        record.addedNodes.forEach((n) => n instanceof view.Element && observer.observe(n));
      }
      update();
    });
    children.observe(el, { childList: true });
    return () => {
      observer.disconnect();
      children.disconnect();
    };
  }, [update]);

  return { ref, edges, onScroll: update };
}
