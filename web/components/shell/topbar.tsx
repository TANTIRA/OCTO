"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { ChevronRight, Menu, Search } from "lucide-react";
import { cn } from "@/lib/utils";
import { useFormat } from "@/lib/use-format";
import { AS_OF } from "@/lib/demo";
import { IconButton, ring } from "@/components/ui/button";
import { Kbd } from "@/components/ui/badge";
import { breadcrumb, type Crumb } from "./nav-config";
import { useShell } from "./shell-context";
import { NotificationCenter, ThemeSwitcher } from "./topbar-menus";

export function Breadcrumbs({ crumbs }: { crumbs: Crumb[] }) {
  return (
    <nav aria-label="Breadcrumb" className="min-w-0">
      <ol className="flex items-center gap-1.5 text-[13px]">
        {crumbs.map((c, i) => {
          const last = i === crumbs.length - 1;
          return (
            <li key={`${i}-${c.label}`} className={cn("flex min-w-0 items-center gap-1.5", !last && "max-sm:hidden")}>
              {i > 0 && <ChevronRight aria-hidden className="size-3.5 shrink-0 text-ink-4" />}
              {c.href && !last ? (
                <Link href={c.href} className={cn("truncate rounded-sm font-medium text-ink-3 hover:text-ink", ring)}>
                  {c.label}
                </Link>
              ) : (
                <span aria-current={last ? "page" : undefined} className={cn("truncate font-medium", last ? "text-ink" : "text-ink-3")}>
                  {c.label}
                </span>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/**
 * Topbar (V2 TOP-001/002/003, V3 THEME-001): breadcrumb left; data date, search,
 * notifications and theme right (shortcuts remain on the ? key). The QTD/YTD/LTM/ITD switch was removed — it
 * changed no figures — and system status lives in the sidebar footer.
 */
export function Topbar({ onOpenDrawer }: { onOpenDrawer: () => void }) {
  const pathname = usePathname();
  const { crumbs, openCommand } = useShell();
  const f = useFormat();

  return (
    <header className="flex h-14 shrink-0 items-center gap-2 border-b border-line bg-app px-3 sm:gap-3 sm:px-6 lg:px-8">
      <IconButton className="lg:hidden" label="Open navigation" icon={<Menu />} onClick={onOpenDrawer} />
      <Breadcrumbs crumbs={crumbs ?? breadcrumb(pathname)} />

      <div className="ml-auto flex items-center gap-2">
        <span className="hidden whitespace-nowrap text-[12px] text-ink-3 lg:inline">Data as of {f.date(AS_OF)}</span>

        <button
          type="button"
          onClick={openCommand}
          aria-label="Search and commands"
          aria-keyshortcuts="Control+K Meta+K /"
          className={cn("flex h-9 cursor-pointer items-center gap-2.5 rounded-md border border-line bg-surface px-3 text-[13px] text-ink-4 transition-colors hover:border-line-strong hover:text-ink-3 max-md:size-9 max-md:justify-center max-md:px-0 md:w-60 xl:w-[272px]", ring)}
        >
          <Search aria-hidden className="size-4 shrink-0 text-ink-3" />
          <span className="hidden flex-1 truncate text-left md:inline">Search entities, reports, workflows…</span>
          <Kbd className="hidden md:inline-flex">/</Kbd>
        </button>

        <div className="flex items-center gap-1.5">
          <NotificationCenter />
          <ThemeSwitcher />
        </div>
      </div>
    </header>
  );
}
