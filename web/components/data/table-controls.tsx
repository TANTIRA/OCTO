"use client";

import { useState } from "react";
import { ArrowDown, ArrowUp, Bookmark, Check, ChevronLeft, ChevronRight, Columns3, Link2, Pin, PinOff, Rows3, Trash2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { usePreferences } from "@/lib/preferences";
import { Button, IconButton, ringInset } from "@/components/ui/button";
import { Checkbox, Input } from "@/components/ui/controls";
import { PopoverPanel, useDismissable } from "@/components/ui/overlay";
import type { SavedView } from "./table-state";

/* ---------- Column manager: visibility, order, pinning ---------- */

export function ColumnManager({ columns, hidden, pinned, onToggle, onMove, onPin, onReset }: { columns: { id: string; header: string; hideable?: boolean }[]; hidden: string[]; pinned: string[]; onToggle: (id: string) => void; onMove: (id: string, dir: -1 | 1) => void; onPin: (id: string) => void; onReset: () => void }) {
  const { open, setOpen, rootRef, triggerRef } = useDismissable();
  return (
    <div ref={rootRef} className="relative">
      <Button ref={triggerRef} size="sm" aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen(!open)}>
        <Columns3 /> <span className="hidden sm:inline">Columns</span>
      </Button>
      {open && (
        <PopoverPanel role="dialog" aria-label="Manage columns" className="w-72 p-1">
          <ul>
            {columns.map((c, i) => {
              const locked = c.hideable === false;
              return (
                <li key={c.id} className="flex items-center gap-1 rounded-md px-1.5 py-1 hover:bg-hover">
                  <Checkbox label={`Show ${c.header}`} checked={!hidden.includes(c.id)} onChange={() => !locked && onToggle(c.id)} className={locked ? "opacity-50" : undefined} />
                  <span className={cn("ml-1 flex-1 truncate text-[13px]", locked && "text-ink-3")}>{c.header}</span>
                  <IconButton size="xs" label={pinned.includes(c.id) ? `Unpin ${c.header}` : `Pin ${c.header}`} icon={pinned.includes(c.id) ? <PinOff /> : <Pin />} onClick={() => onPin(c.id)} className={pinned.includes(c.id) ? "text-accent" : undefined} />
                  <IconButton size="xs" label={`Move ${c.header} up`} icon={<ArrowUp />} disabled={i === 0} onClick={() => onMove(c.id, -1)} />
                  <IconButton size="xs" label={`Move ${c.header} down`} icon={<ArrowDown />} disabled={i === columns.length - 1} onClick={() => onMove(c.id, 1)} />
                </li>
              );
            })}
          </ul>
          <button type="button" onClick={onReset} className={cn("mt-1 w-full cursor-pointer rounded-md border-t border-line px-2 py-1.5 text-left text-[12px] text-accent hover:bg-hover", ringInset)}>
            Reset columns
          </button>
        </PopoverPanel>
      )}
    </div>
  );
}

/* ---------- Saved views ---------- */

export function SavedViewPicker({ views, activeId, dirty, onSelect, onSave, onDelete, onShare }: { views: SavedView[]; activeId: string | null; dirty: boolean; onSelect: (v: SavedView) => void; onSave: (name: string) => void; onDelete: (id: string) => void; onShare: () => void }) {
  const { open, setOpen, rootRef, triggerRef } = useDismissable();
  const [name, setName] = useState("");
  const active = views.find((v) => v.id === activeId);
  return (
    <div ref={rootRef} className="relative">
      <Button ref={triggerRef} size="sm" aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen(!open)}>
        <Bookmark /> <span className="max-w-32 truncate">{active ? active.name : "Views"}</span>
        {dirty && active && <span aria-label="Modified" className="size-1.5 rounded-full bg-accent" />}
      </Button>
      {open && (
        <PopoverPanel align="start" role="dialog" aria-label="Saved views" className="w-72 p-1">
          <ul>
            {views.map((v) => (
              <li key={v.id} className="flex items-center gap-1 rounded-md hover:bg-hover">
                <button type="button" onClick={() => (onSelect(v), setOpen(false))} className={cn("flex flex-1 cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-[13px]", ringInset)}>
                  <Check aria-hidden className={cn("size-3.5", v.id === activeId ? "text-accent" : "invisible")} />
                  <span className="flex-1 truncate">{v.name}</span>
                  {v.builtIn && <span className="text-[10px] font-semibold uppercase tracking-[0.05em] text-ink-4">Default</span>}
                </button>
                {!v.builtIn && <IconButton size="xs" label={`Delete view ${v.name}`} icon={<Trash2 />} onClick={() => onDelete(v.id)} />}
              </li>
            ))}
          </ul>
          <form
            className="mt-1 flex gap-1 border-t border-line p-1 pt-2"
            onSubmit={(e) => {
              e.preventDefault();
              if (name.trim()) {
                onSave(name.trim());
                setName("");
                setOpen(false);
              }
            }}
          >
            <Input aria-label="New view name" placeholder="Save current view as…" value={name} onChange={(e) => setName(e.target.value)} className="h-7 flex-1" />
            <Button size="sm" type="submit" disabled={!name.trim()}>
              Save
            </Button>
          </form>
          <button type="button" onClick={() => (onShare(), setOpen(false))} className={cn("mt-1 flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left text-[12px] text-ink-2 hover:bg-hover", ringInset)}>
            <Link2 aria-hidden className="size-3.5" /> Copy link to this view
          </button>
        </PopoverPanel>
      )}
    </div>
  );
}

/* ---------- Density (global preference) ---------- */

export function DensityToggle() {
  const { density, setDensity } = usePreferences();
  return (
    <IconButton
      size="sm"
      variant="secondary"
      label={density === "compact" ? "Switch to comfortable rows" : "Switch to compact rows"}
      aria-pressed={density === "comfortable"}
      icon={<Rows3 />}
      onClick={() => setDensity(density === "compact" ? "comfortable" : "compact")}
    />
  );
}

/* ---------- Pagination ---------- */

export function Pagination({ page, pageCount, total, pageSize, onPage }: { page: number; pageCount: number; total: number; pageSize: number; onPage: (p: number) => void }) {
  const from = total === 0 ? 0 : page * pageSize + 1;
  const to = Math.min(total, (page + 1) * pageSize);
  return (
    <nav aria-label="Pagination" className="flex items-center gap-2">
      <span className="tabular-nums">
        {from}–{to} of {total}
      </span>
      <IconButton size="xs" label="Previous page" icon={<ChevronLeft />} disabled={page === 0} onClick={() => onPage(page - 1)} />
      <span className="tabular-nums">
        {page + 1} / {Math.max(1, pageCount)}
      </span>
      <IconButton size="xs" label="Next page" icon={<ChevronRight />} disabled={page >= pageCount - 1} onClick={() => onPage(page + 1)} />
    </nav>
  );
}
