"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";
import { usePreferences } from "@/lib/preferences";
import { IconButton } from "@/components/ui/button";
import { CommandMenu } from "./command-menu";
import { useShell } from "./shell-context";
import { GO_TO } from "./shortcuts";
import { Sidebar } from "./sidebar";
import { Topbar } from "./topbar";
import { ShortcutsDialog } from "./topbar-menus";

/**
 * Application shell (plan §8). Owns layout and global keyboard handling only;
 * pages own their data. Sidebar 248 / 56px, drawer below 1024px, topbar,
 * command menu, and the shortcuts dialog.
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const { sidebarCollapsed, setSidebarCollapsed } = usePreferences();
  const { setOpenCommand } = useShell();
  const [commandOpen, setCommandOpen] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  const pathname = usePathname();
  const router = useRouter();
  const pendingG = useRef<number | null>(null);

  useEffect(() => setOpenCommand(() => setCommandOpen(true)), [setOpenCommand]);
  useEffect(() => setDrawerOpen(false), [pathname]);

  const closeShortcuts = useCallback(() => setShortcutsOpen(false), []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      const typing = el?.closest("input, textarea, select, [contenteditable=true]");
      const mod = e.metaKey || e.ctrlKey;
      if (mod && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setCommandOpen((v) => !v);
        return;
      }
      if (typing || mod || e.altKey) return;
      if (drawerOpen && e.key === "Escape") return setDrawerOpen(false);
      if (pendingG.current !== null) {
        window.clearTimeout(pendingG.current);
        pendingG.current = null;
        const href = GO_TO[e.key.toLowerCase()];
        if (href) {
          e.preventDefault();
          router.push(href);
        }
        return;
      }
      if (e.key === "/") {
        e.preventDefault();
        setCommandOpen(true);
      } else if (e.key === "[") {
        setSidebarCollapsed(!sidebarCollapsed);
      } else if (e.key === "?") {
        setShortcutsOpen(true);
      } else if (e.key === "g") {
        pendingG.current = window.setTimeout(() => (pendingG.current = null), 900);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [sidebarCollapsed, setSidebarCollapsed, drawerOpen, router]);

  return (
    <div className="flex h-dvh w-full overflow-hidden bg-app text-ink">
      <a href="#main" className="sr-only z-[100] rounded-md bg-accent-fill px-3 py-2 text-sm text-white focus:not-sr-only focus:fixed focus:left-3 focus:top-3">
        Skip to content
      </a>

      <aside className={cn("hidden shrink-0 border-r border-line bg-subtle transition-[width] duration-200 ease-standard lg:block", sidebarCollapsed ? "w-14" : "w-[248px]")}>
        <Sidebar collapsed={sidebarCollapsed} onToggle={() => setSidebarCollapsed(!sidebarCollapsed)} onShortcuts={() => setShortcutsOpen(true)} />
      </aside>

      {drawerOpen && (
        <div className="fixed inset-0 z-[60] lg:hidden" role="dialog" aria-modal="true" aria-label="Navigation">
          <div aria-hidden className="absolute inset-0 bg-black/40 motion-safe:animate-[fade-in_160ms_ease-out]" onClick={() => setDrawerOpen(false)} />
          <aside className="absolute inset-y-0 left-0 w-[280px] max-w-[85vw] border-r border-line bg-subtle shadow-dialog motion-safe:animate-[slide-in-left_200ms_var(--ease-out-soft)]">
            <div className="absolute right-2 top-3 z-10">
              <IconButton size="sm" label="Close navigation" icon={<X />} onClick={() => setDrawerOpen(false)} />
            </div>
            <Sidebar collapsed={false} onShortcuts={() => setShortcutsOpen(true)} />
          </aside>
        </div>
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar onOpenDrawer={() => setDrawerOpen(true)} />
        <main id="main" tabIndex={-1} className="min-h-0 flex-1 overflow-y-auto focus:outline-none">
          {children}
        </main>
      </div>

      <CommandMenu open={commandOpen} onClose={() => setCommandOpen(false)} />
      <ShortcutsDialog open={shortcutsOpen} onClose={closeShortcuts} />
    </div>
  );
}
