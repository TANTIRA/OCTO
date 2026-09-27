"use client";

import { useCallback, useId, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { GripVertical, MessageSquare, Paperclip, Plus, X } from "lucide-react";

const cx = (...c: (string | false | null | undefined)[]) =>
  c.filter(Boolean).join(" ");

const focus =
  "focus-visible:outline-none focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[var(--rb-accent,oklch(20.5%_0_0))] dark:focus-visible:outline-[var(--rb-accent,oklch(100%_0_0))]";

const transition =
  "transition-[background-color,border-color,color,opacity,box-shadow] duration-150 ease-out";

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];
const SPRING = { type: "spring" as const, bounce: 0, duration: 0.32 };

type Card = {
  id: string;
  ref: string;
  title: string;
  tag: string;
  assignee: string;
  comments: number;
  files: number;
  priority: "High" | "Medium" | "Low";
};

const CARDS: Record<string, Card> = {
  h1: {
    id: "h1",
    ref: "D-241",
    title: "Meridian Health — Series B follow-on",
    tag: "Healthcare",
    assignee: "Ayu Wijaya",
    comments: 4,
    files: 2,
    priority: "High",
  },
  h2: {
    id: "h2",
    ref: "D-238",
    title: "Cipta Logistics — cold-chain expansion",
    tag: "Logistics",
    assignee: "Rafi Tan",
    comments: 7,
    files: 5,
    priority: "High",
  },
  h3: {
    id: "h3",
    ref: "D-233",
    title: "Aruna Payments — co-invest allocation",
    tag: "Fintech",
    assignee: "Mei Chen",
    comments: 3,
    files: 1,
    priority: "Medium",
  },
  h4: {
    id: "h4",
    ref: "D-229",
    title: "Solus Energy — private credit facility",
    tag: "Credit",
    assignee: "Dimas Prasetyo",
    comments: 5,
    files: 4,
    priority: "Medium",
  },
  h5: {
    id: "h5",
    ref: "D-246",
    title: "Nusantara Foods — carve-out sourcing",
    tag: "Consumer",
    assignee: "Sari Lim",
    comments: 1,
    files: 0,
    priority: "Low",
  },
  h6: {
    id: "h6",
    ref: "D-217",
    title: "Kirana Consumer — platform add-on",
    tag: "Consumer",
    assignee: "Ayu Wijaya",
    comments: 9,
    files: 6,
    priority: "High",
  },
  h7: {
    id: "h7",
    ref: "D-235",
    title: "Helios Data Centers — DD workstream",
    tag: "Infrastructure",
    assignee: "Jonas Weber",
    comments: 2,
    files: 3,
    priority: "High",
  },
  h8: {
    id: "h8",
    ref: "D-208",
    title: "Anchor Re — exit documentation",
    tag: "Insurance",
    assignee: "Mei Chen",
    comments: 6,
    files: 8,
    priority: "Medium",
  },
  h9: {
    id: "h9",
    ref: "D-244",
    title: "Surya Agro — broker teaser review",
    tag: "Agribusiness",
    assignee: "Rafi Tan",
    comments: 0,
    files: 1,
    priority: "Low",
  },
  h10: {
    id: "h10",
    ref: "D-230",
    title: "Bharat Mobility — DDQ responses",
    tag: "Mobility",
    assignee: "Dimas Prasetyo",
    comments: 4,
    files: 2,
    priority: "Medium",
  },
};

const COLUMNS = [
  { id: "backlog", name: "Sourced", limit: 0 },
  { id: "progress", name: "Screening", limit: 6 },
  { id: "review", name: "IC review", limit: 3 },
  { id: "done", name: "Closed", limit: 0 },
] as const;

type ColumnId = (typeof COLUMNS)[number]["id"];

const INITIAL: Record<ColumnId, string[]> = {
  backlog: ["h1", "h5", "h9"],
  progress: ["h2", "h7", "h4"],
  review: ["h3", "h10"],
  done: ["h6", "h8"],
};

const initials = (name: string) =>
  name
    .split(/[\s-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase())
    .join("");

const PRIORITY_DOT: Record<Card["priority"], string> = {
  High: "bg-[var(--rb-accent,oklch(20.5%_0_0))] dark:bg-[var(--rb-accent,oklch(100%_0_0))]",
  Medium: "bg-neutral-400 dark:bg-neutral-500",
  Low: "bg-neutral-200 dark:bg-neutral-700",
};

export default function Kanban1() {
  const uid = useId();
  const reduce = useReducedMotion();

  const [items, setItems] = useState<Record<ColumnId, string[]>>(INITIAL);
  const [cards, setCards] = useState<Record<string, Card>>(CARDS);
  const [dragId, setDragId] = useState<string | null>(null);
  const [grabbed, setGrabbed] = useState<string | null>(null);
  const [ghost, setGhost] = useState({ x: 0, y: 0, w: 0 });
  const [composing, setComposing] = useState<ColumnId | null>(null);
  const [draft, setDraft] = useState("");
  const [announce, setAnnounce] = useState("");

  const rootRef = useRef<HTMLDivElement>(null);
  const colRefs = useRef(new Map<ColumnId, HTMLElement>());
  const seq = useRef(0);
  const dragRef = useRef<{
    id: string;
    dx: number;
    dy: number;
    w: number;
    active: boolean;
    startX: number;
    startY: number;
    pointerId: number;
  } | null>(null);

  const columnOf = useCallback(
    (id: string) =>
      (Object.keys(items) as ColumnId[]).find((c) => items[c].includes(id)) ??
      "backlog",
    [items],
  );

  const move = useCallback((id: string, toCol: ColumnId, toIndex: number) => {
    setItems((prev) => {
      const next = { ...prev } as Record<ColumnId, string[]>;
      let from: ColumnId | null = null;
      for (const c of Object.keys(next) as ColumnId[]) {
        if (next[c].includes(id)) from = c;
      }
      if (!from) return prev;
      const fromIndex = next[from].indexOf(id);
      if (from === toCol && fromIndex === toIndex) return prev;
      next[from] = next[from].filter((x) => x !== id);
      const list = from === toCol ? next[from] : [...next[toCol]];
      list.splice(Math.max(0, Math.min(toIndex, list.length)), 0, id);
      next[toCol] = list;
      return next;
    });
  }, []);

  const targetFromPoint = useCallback(
    (clientX: number, clientY: number, id: string) => {
      for (const [colId, el] of colRefs.current) {
        const r = el.getBoundingClientRect();
        if (clientX < r.left || clientX > r.right) continue;
        const others = Array.from(
          el.querySelectorAll<HTMLElement>("[data-card]"),
        ).filter((c) => c.dataset.card !== id);
        let index = others.length;
        for (let i = 0; i < others.length; i++) {
          const cr = others[i].getBoundingClientRect();
          if (clientY < cr.top + cr.height / 2) {
            index = i;
            break;
          }
        }
        return { col: colId, index };
      }
      return null;
    },
    [],
  );

  const onPointerDown = (e: React.PointerEvent<HTMLElement>, id: string) => {
    if (e.button !== 0) return;
    const el = e.currentTarget.closest<HTMLElement>("[data-card]");
    const root = rootRef.current;
    if (!el || !root) return;
    e.preventDefault();
    const r = el.getBoundingClientRect();
    dragRef.current = {
      id,
      dx: e.clientX - r.left,
      dy: e.clientY - r.top,
      w: r.width,
      active: false,
      startX: e.clientX,
      startY: e.clientY,
      pointerId: e.pointerId,
    };
    root.setPointerCapture(e.pointerId);
    el.querySelector<HTMLElement>(
      '[aria-roledescription="Draggable card"]',
    )?.focus();
  };

  const onPointerMove = (e: React.PointerEvent<HTMLElement>) => {
    const d = dragRef.current;
    const root = rootRef.current;
    if (!d || !root) return;
    if (!d.active) {
      if (Math.hypot(e.clientX - d.startX, e.clientY - d.startY) < 4) return;
      d.active = true;
      setGrabbed(null);
      setDragId(d.id);
    }
    const rr = root.getBoundingClientRect();
    setGhost({
      x: e.clientX - rr.left - d.dx,
      y: e.clientY - rr.top - d.dy,
      w: d.w,
    });
    const t = targetFromPoint(e.clientX, e.clientY, d.id);
    if (t) move(d.id, t.col as ColumnId, t.index);
  };

  const endDrag = () => {
    const d = dragRef.current;
    if (!d) return;
    dragRef.current = null;
    const root = rootRef.current;
    if (root?.hasPointerCapture?.(d.pointerId))
      root.releasePointerCapture(d.pointerId);
    if (d.active) {
      const col = COLUMNS.find((c) => c.id === columnOf(d.id));
      setAnnounce(`${cards[d.id]?.ref} moved to ${col?.name}.`);
    }
    setDragId(null);
  };

  const onCardKeyDown = (e: React.KeyboardEvent<HTMLElement>, id: string) => {
    const col = columnOf(id);
    const index = items[col].indexOf(id);
    const colIndex = COLUMNS.findIndex((c) => c.id === col);

    if (e.key === "" || e.key === "Enter") {
      e.preventDefault();
      const next = grabbed === id ? null : id;
      setGrabbed(next);
      setAnnounce(
        next
          ? `${cards[id].ref} grabbed. Use the arrow keys to move it, then press space to drop.`
          : `${cards[id].ref} dropped in ${COLUMNS[colIndex].name}.`,
      );
      return;
    }
    if (e.key === "Escape" && grabbed === id) {
      e.preventDefault();
      setGrabbed(null);
      setAnnounce("Move cancelled.");
      return;
    }
    if (grabbed !== id) return;

    let handled = true;
    if (e.key === "ArrowUp") move(id, col, Math.max(0, index - 1));
    else if (e.key === "ArrowDown") move(id, col, index + 1);
    else if (e.key === "ArrowLeft" && colIndex > 0)
      move(
        id,
        COLUMNS[colIndex - 1].id,
        items[COLUMNS[colIndex - 1].id].length,
      );
    else if (e.key === "ArrowRight" && colIndex < COLUMNS.length - 1)
      move(
        id,
        COLUMNS[colIndex + 1].id,
        items[COLUMNS[colIndex + 1].id].length,
      );
    else handled = false;

    if (handled) {
      e.preventDefault();
      e.currentTarget.focus();
    }
  };

  const addCard = (col: ColumnId) => {
    const title = draft.trim();
    if (!title) {
      setComposing(null);
      return;
    }
    const n = 246 + seq.current;
    const id = `n${n}`;
    seq.current += 1;
    setCards((p) => ({
      ...p,
      [id]: {
        id,
        ref: `D-${n}`,
        title,
        tag: "New",
        assignee: "Unassigned",
        comments: 0,
        files: 0,
        priority: "Medium",
      },
    }));
    setItems((p) => ({ ...p, [col]: [id, ...p[col]] }));
    setDraft("");
    setComposing(null);
    setAnnounce(`Added ${title}.`);
  };

  const total = useMemo(
    () => Object.values(items).reduce((s, l) => s + l.length, 0),
    [items],
  );

  return (
    <div
      ref={rootRef}
      onPointerMove={onPointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onLostPointerCapture={endDrag}
      className={cx(
        "relative flex h-full min-h-[680px] w-full flex-col overflow-hidden bg-white dark:bg-neutral-950",
        dragId && "touch-none select-none",
      )}
    >
      <header className="flex shrink-0 flex-wrap items-end justify-between gap-3 px-6 pt-6 pb-4 sm:px-8">
        <div className="min-w-0">
          <h2 className="text-base font-medium tracking-[-0.01em] text-neutral-900 dark:text-neutral-100">
            Deal pipeline
          </h2>
          <p className="mt-0.5 truncate text-[13px] text-neutral-500">
            {total} open deals. Drag a card, or focus one and press space to
            move it with the arrow keys.
          </p>
        </div>
        <div className="flex shrink-0 -space-x-1.5">
          {["Ayu Wijaya", "Rafi Tan", "Mei Chen", "Dimas Prasetyo"].map(
            (p) => (
              <span
                key={p}
                title={p}
                className="inline-flex h-7 w-7 items-center justify-center rounded-full border border-white bg-neutral-100 text-[10px] font-medium text-neutral-600 dark:border-neutral-950 dark:bg-neutral-800 dark:text-neutral-300"
              >
                {initials(p)}
              </span>
            ),
          )}
        </div>
      </header>

      <div className="min-h-0 flex-1 overflow-x-auto overflow-y-hidden px-6 pb-6 sm:px-8">
        <div className="flex h-full min-w-max gap-2.5">
          {COLUMNS.map((col) => {
            const list = items[col.id];
            const over = col.limit > 0 && list.length > col.limit;
            return (
              <section
                key={col.id}
                aria-label={col.name}
                className="flex h-full w-[264px] shrink-0 flex-col rounded-[var(--rb-r-2xl,14px)] border border-neutral-200/70 bg-neutral-50 p-1 dark:border-neutral-800 dark:bg-neutral-950"
              >
                <div className="flex h-8 shrink-0 items-center gap-2 px-2">
                  <span className="truncate text-[13px] font-medium text-neutral-900 dark:text-neutral-100">
                    {col.name}
                  </span>
                  <span
                    className={cx(
                      "text-[11px] tabular-nums",
                      over
                        ? "font-medium text-neutral-900 dark:text-white"
                        : "text-neutral-500",
                    )}
                  >
                    {list.length}
                    {col.limit > 0 ? `/${col.limit}` : ""}
                  </span>
                  <button
                    type="button"
                    onClick={() => {
                      setComposing(col.id);
                      setDraft("");
                    }}
                    aria-label={`Add a card to ${col.name}`}
                    className={cx(
                      "ml-auto inline-flex h-6 w-6 cursor-pointer items-center justify-center rounded-[var(--rb-r-sm,6px)] text-neutral-500 hover:bg-neutral-200/70 hover:text-neutral-900 dark:hover:bg-neutral-800 dark:hover:text-neutral-100",
                      transition,
                      focus,
                    )}
                  >
                    <Plus className="h-3.5 w-3.5" strokeWidth={2} />
                  </button>
                </div>

                <div
                  ref={(el) => {
                    if (el) colRefs.current.set(col.id, el);
                    else colRefs.current.delete(col.id);
                  }}
                  className="min-h-0 flex-1 overflow-y-auto p-1 pt-0"
                >
                  <AnimatePresence initial={false}>
                    {composing === col.id && (
                      <motion.div
                        key="composer"
                        initial={
                          reduce ? { opacity: 0 } : { opacity: 0, height: 0 }
                        }
                        animate={
                          reduce
                            ? { opacity: 1 }
                            : { opacity: 1, height: "auto" }
                        }
                        exit={
                          reduce
                            ? { opacity: 0, transition: { duration: 0.12 } }
                            : {
                                opacity: 0,
                                height: 0,
                                transition: { duration: 0.16, ease: EASE_OUT },
                              }
                        }
                        transition={{ duration: 0.2, ease: EASE_OUT }}
                        className="overflow-hidden"
                      >
                        <div className="mb-1 rounded-[var(--rb-r-lg,10px)] border border-neutral-200/70 bg-white p-2 dark:border-neutral-800 dark:bg-neutral-900">
                          <label
                            htmlFor={`${uid}-${col.id}-draft`}
                            className="sr-only"
                          >
                            New card in {col.name}
                          </label>
                          <input
                            id={`${uid}-${col.id}-draft`}
                            autoFocus
                            value={draft}
                            onChange={(e) => setDraft(e.target.value)}
                            onKeyDown={(e) => {
                              if (e.key === "Enter") addCard(col.id);
                              if (e.key === "Escape") setComposing(null);
                            }}
                            placeholder="New prospect…"
                            className={cx(
                              "h-8 w-full rounded-[var(--rb-r-sm,6px)] bg-transparent px-1 text-[13px] text-neutral-900 placeholder:text-neutral-400 dark:text-neutral-100",
                              focus,
                            )}
                          />
                          <div className="mt-1 flex items-center gap-1">
                            <button
                              type="button"
                              onClick={() => addCard(col.id)}
                              className={cx(
                                "inline-flex h-7 cursor-pointer items-center rounded-[var(--rb-r-sm,6px)] bg-[var(--rb-accent,oklch(20.5%_0_0))] px-2.5 text-[12px] font-medium text-[var(--rb-accent-fg,oklch(100%_0_0))] hover:bg-[color-mix(in_oklab,var(--rb-accent,oklch(20.5%_0_0))_90%,transparent)] dark:bg-[var(--rb-accent,oklch(100%_0_0))] dark:text-[var(--rb-accent-fg,oklch(20.5%_0_0))] dark:hover:bg-[color-mix(in_oklab,var(--rb-accent,oklch(100%_0_0))_90%,transparent)]",
                                transition,
                                focus,
                              )}
                            >
                              Add
                            </button>
                            <button
                              type="button"
                              onClick={() => setComposing(null)}
                              aria-label="Cancel"
                              className={cx(
                                "inline-flex h-7 w-7 cursor-pointer items-center justify-center rounded-[var(--rb-r-sm,6px)] text-neutral-500 hover:bg-neutral-100 hover:text-neutral-900 dark:hover:bg-neutral-800 dark:hover:text-neutral-100",
                                transition,
                                focus,
                              )}
                            >
                              <X className="h-3.5 w-3.5" strokeWidth={2} />
                            </button>
                          </div>
                        </div>
                      </motion.div>
                    )}
                  </AnimatePresence>

                  <ul className="space-y-1">
                    {list.map((id) => {
                      const card = cards[id];
                      if (!card) return null;
                      const isDragging = dragId === id;
                      const isGrabbed = grabbed === id;
                      return (
                        <motion.li
                          key={id}
                          layout={reduce ? false : "position"}
                          transition={SPRING}
                          data-card={id}
                          className={cx(
                            "rounded-[var(--rb-r-lg,10px)]",
                            isDragging &&
                              "border border-dashed border-neutral-300 bg-neutral-100/60 dark:border-neutral-700 dark:bg-neutral-900/60",
                          )}
                        >
                          <div
                            role="button"
                            tabIndex={0}
                            aria-roledescription="Draggable card"
                            aria-label={`${card.ref}: ${card.title}. ${card.priority} priority, assigned to ${card.assignee}.`}
                            onPointerDown={(e) => {
                              if (e.pointerType === "mouse")
                                onPointerDown(e, id);
                            }}
                            onKeyDown={(e) => onCardKeyDown(e, id)}
                            className={cx(
                              "group relative flex w-full cursor-grab flex-col gap-2 rounded-[var(--rb-r-lg,10px)] border bg-white p-2.5 text-left dark:bg-neutral-900",
                              isGrabbed
                                ? "border-neutral-900 dark:border-white"
                                : "border-neutral-200/70 hover:border-neutral-300 dark:border-neutral-800 dark:hover:border-neutral-700",
                              isDragging && "invisible",
                              transition,
                              focus,
                            )}
                          >
                            <div className="flex items-center gap-1.5">
                              <span
                                aria-hidden
                                className={cx(
                                  "h-1.5 w-1.5 shrink-0 rounded-full",
                                  PRIORITY_DOT[card.priority],
                                )}
                              />
                              <span className="text-[11px] tabular-nums text-neutral-500">
                                {card.ref}
                              </span>
                              <span
                                onPointerDown={(e) => onPointerDown(e, id)}
                                style={{ touchAction: "none" }}
                                aria-hidden
                                className="ml-auto -mr-0.5 cursor-grab text-neutral-300 group-hover:text-neutral-400 dark:text-neutral-700 dark:group-hover:text-neutral-600"
                              >
                                <GripVertical
                                  className="h-3.5 w-3.5"
                                  strokeWidth={2}
                                />
                              </span>
                            </div>

                            <p className="text-[13px] leading-snug text-neutral-900 dark:text-neutral-100">
                              {card.title}
                            </p>

                            <div className="flex items-center gap-2">
                              <span className="inline-flex h-5 items-center rounded-[var(--rb-r-sm,6px)] bg-neutral-100 px-1.5 text-[11px] text-neutral-600 dark:bg-neutral-800 dark:text-neutral-300">
                                {card.tag}
                              </span>
                              {card.comments > 0 && (
                                <span className="inline-flex items-center gap-1 text-[11px] tabular-nums text-neutral-500">
                                  <MessageSquare
                                    className="h-3 w-3"
                                    strokeWidth={2}
                                  />
                                  {card.comments}
                                </span>
                              )}
                              {card.files > 0 && (
                                <span className="inline-flex items-center gap-1 text-[11px] tabular-nums text-neutral-500">
                                  <Paperclip
                                    className="h-3 w-3"
                                    strokeWidth={2}
                                  />
                                  {card.files}
                                </span>
                              )}
                              <span
                                title={card.assignee}
                                className="ml-auto inline-flex h-6 w-6 items-center justify-center rounded-full bg-neutral-100 text-[10px] font-medium text-neutral-600 dark:bg-neutral-800 dark:text-neutral-300"
                              >
                                {initials(card.assignee)}
                              </span>
                            </div>
                          </div>
                        </motion.li>
                      );
                    })}
                  </ul>

                  {list.length === 0 && composing !== col.id && (
                    <p className="px-2 py-6 text-center text-[12px] text-neutral-400 dark:text-neutral-600">
                      Nothing here yet
                    </p>
                  )}
                </div>
              </section>
            );
          })}
        </div>
      </div>

      <AnimatePresence>
        {dragId && cards[dragId] && (
          <motion.div
            key="ghost"
            initial={reduce ? { opacity: 0.9 } : { opacity: 0.9, scale: 1 }}
            animate={
              reduce
                ? { opacity: 1 }
                : { opacity: 1, scale: 1.02, rotate: -1.5 }
            }
            exit={{ opacity: 0, transition: { duration: 0.1 } }}
            transition={{ duration: 0.15, ease: EASE_OUT }}
            style={{
              left: ghost.x,
              top: ghost.y,
              width: ghost.w,
            }}
            className="pointer-events-none absolute z-50 rounded-[var(--rb-r-lg,10px)] border border-neutral-200/70 bg-white p-2.5 shadow-[0_12px_32px_-12px_rgba(0,0,0,0.35)] dark:border-neutral-700 dark:bg-neutral-900"
          >
            <div className="flex items-center gap-1.5">
              <span
                aria-hidden
                className={cx(
                  "h-1.5 w-1.5 shrink-0 rounded-full",
                  PRIORITY_DOT[cards[dragId].priority],
                )}
              />
              <span className="text-[11px] tabular-nums text-neutral-500">
                {cards[dragId].ref}
              </span>
            </div>
            <p className="mt-2 text-[13px] leading-snug text-neutral-900 dark:text-neutral-100">
              {cards[dragId].title}
            </p>
          </motion.div>
        )}
      </AnimatePresence>

      <p aria-live="polite" className="sr-only">
        {announce}
      </p>
    </div>
  );
}
