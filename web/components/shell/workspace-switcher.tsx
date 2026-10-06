"use client";

import { Check, ChevronsUpDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { useWorkspace } from "@/lib/workspace";
import { ring, ringInset } from "@/components/ui/button";
import { Monogram } from "@/components/ui/badge";
import { PopoverPanel, useDismissable } from "@/components/ui/overlay";

/** Workspace (tenant) switcher. The list comes from /api/v1/me/access or a labelled demo list. */
export function WorkspaceSwitcher({ collapsed }: { collapsed: boolean }) {
  const { workspaces, current, setCurrent, source } = useWorkspace();
  const { open, setOpen, close, rootRef, triggerRef } = useDismissable();
  return (
    <div ref={rootRef} className="relative">
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={`Workspace: ${current.name}. Switch workspace`}
        onClick={() => setOpen(!open)}
        className={cn("flex h-11 w-full cursor-pointer items-center gap-2.5 rounded-lg border border-line bg-surface text-left transition-colors hover:bg-hover", collapsed ? "justify-center border-transparent bg-transparent" : "px-2", ring)}
      >
        <Monogram name={current.name} size="sm" />
        {!collapsed && (
          <>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[13px] font-semibold leading-tight text-ink">{current.name}</span>
              <span className="block truncate text-[11px] leading-tight text-ink-3">{current.detail ?? "Workspace"}</span>
            </span>
            <ChevronsUpDown aria-hidden className="size-3.5 shrink-0 text-ink-3" />
          </>
        )}
      </button>
      {open && (
        <PopoverPanel align="start" className={cn("w-64 p-1", collapsed && "left-[calc(100%+8px)] top-0")}>
          <p className="px-2 pb-1 pt-1.5 text-label uppercase text-ink-4">Workspaces{source === "demo" && " · demo list"}</p>
          <ul role="listbox" aria-label="Workspaces">
            {workspaces.map((w) => (
              <li key={w.slug} role="option" aria-selected={w.slug === current.slug}>
                <button
                  type="button"
                  onClick={() => {
                    setCurrent(w.slug);
                    close();
                  }}
                  className={cn("flex w-full cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left hover:bg-hover", ringInset)}
                >
                  <Monogram name={w.name} size="sm" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[13px] text-ink">{w.name}</span>
                    {w.detail && <span className="block truncate text-[11px] text-ink-3">{w.detail}</span>}
                  </span>
                  {w.slug === current.slug && <Check aria-hidden className="size-3.5 text-accent" />}
                </button>
              </li>
            ))}
          </ul>
        </PopoverPanel>
      )}
    </div>
  );
}
