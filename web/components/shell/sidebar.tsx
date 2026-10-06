"use client";

import { useCallback, useRef } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { cn } from "@/lib/utils";
import { useWorkspace } from "@/lib/workspace";
import { OctoMark } from "@/components/brand/octo-mark";
import { usePlatformAdmin } from "@/lib/use-tenants";
import { IconButton, ring, ringInset } from "@/components/ui/button";
import { CountBadge } from "@/components/ui/badge";
import { Tooltip } from "@/components/ui/overlay";
import { isActive, visibleNav, type NavItem } from "./nav-config";
import { WorkspaceSwitcher } from "./workspace-switcher";
import { UserMenu } from "./user-menu";

/**
 * Sidebar (plan FE-SHELL-002; Vestra: #f8f8f8 chrome, grouped nav with
 * section labels, blue-tinted active row, user anchored at the bottom).
 * 248px expanded / 56px collapsed; the same component renders in the mobile drawer.
 */
export function Sidebar({ collapsed, onToggle, onShortcuts }: { collapsed: boolean; onToggle?: () => void; onShortcuts: () => void }) {
  const pathname = usePathname();
  const nav = visibleNav(usePlatformAdmin());
  const listRef = useRef<HTMLElement>(null);

  // ↑/↓ Home/End move between nav rows.
  const onKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(e.key)) return;
    const rows = Array.from(listRef.current?.querySelectorAll<HTMLElement>("[data-nav-row]") ?? []);
    const i = rows.indexOf(document.activeElement as HTMLElement);
    if (i === -1) return;
    e.preventDefault();
    const n = e.key === "Home" ? 0 : e.key === "End" ? rows.length - 1 : (i + (e.key === "ArrowDown" ? 1 : -1) + rows.length) % rows.length;
    rows[n]?.focus();
  }, []);

  return (
    <div className="flex h-full flex-col">
      <div className={cn("flex h-14 shrink-0 items-center gap-2", collapsed ? "justify-center px-0" : "justify-between px-4")}>
        <Link href="/app" aria-label="OCTO — Control Center" className={cn("flex items-center gap-2 rounded-md", ring)}>
          <OctoMark className="h-7 w-8 text-ink" />
          {!collapsed && (
            <span className="leading-tight">
              <span className="block text-[14px] font-semibold tracking-[-0.01em] text-ink">OCTO</span>
              <span className="block text-[11px] text-ink-3">Investment OS</span>
            </span>
          )}
        </Link>
        {onToggle && !collapsed && <IconButton size="sm" label="Collapse sidebar  [" icon={<PanelLeftClose />} onClick={onToggle} aria-expanded />}
      </div>

      <div className={cn("shrink-0 pb-2", collapsed ? "px-2" : "px-3")}>
        <WorkspaceSwitcher collapsed={collapsed} />
      </div>

      <nav ref={listRef} aria-label="Primary" onKeyDown={onKeyDown} className={cn("min-h-0 flex-1 overflow-y-auto pb-3", collapsed ? "px-2" : "px-3")}>
        {nav.map((group) => (
          <div key={group.label} className="mt-3 first:mt-1">
            {!collapsed ? <p className="px-2.5 pb-1.5 text-label uppercase text-ink-4">{group.label}</p> : <div aria-hidden className="mx-2 mb-2 h-px bg-line" />}
            <ul className="space-y-0.5">
              {group.items.map((item) => (
                <li key={item.id}>
                  <NavRow item={item} active={isActive(item, pathname)} collapsed={collapsed} />
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>

      <div className={cn("shrink-0 space-y-2 border-t border-line py-3", collapsed ? "px-2" : "px-3")}>
        <ApiIndicator collapsed={collapsed} />
        <UserMenu collapsed={collapsed} onShortcuts={onShortcuts} />
        {onToggle && collapsed && (
          <div className="flex justify-center">
            <IconButton size="sm" label="Expand sidebar  [" icon={<PanelLeftOpen />} onClick={onToggle} aria-expanded={false} />
          </div>
        )}
      </div>
    </div>
  );
}

function NavRow({ item, active, collapsed }: { item: NavItem; active: boolean; collapsed: boolean }) {
  const Icon = item.icon;
  const base = cn("relative flex h-8 w-full items-center gap-2.5 rounded-md text-[13px] font-medium transition-colors duration-150", collapsed ? "justify-center px-0" : "px-2.5", ringInset);
  const row = item.planned ? (
    <span data-nav-row tabIndex={0} aria-disabled="true" className={cn(base, "cursor-default text-ink-4")}>
      <Icon aria-hidden className="size-4 shrink-0" />
      {!collapsed && (
        <>
          <span className="flex-1 truncate">{item.label}</span>
          <span className="text-[10px] font-semibold uppercase tracking-[0.05em]">Planned</span>
        </>
      )}
    </span>
  ) : (
    <Link
      data-nav-row
      href={item.href}
      aria-current={active ? "page" : undefined}
      className={cn(base, active ? "border border-line bg-accent-soft text-accent-ink" : "border border-transparent text-ink-2 hover:bg-hover hover:text-ink")}
    >
      <Icon aria-hidden className={cn("size-4 shrink-0", active ? "text-accent" : "text-ink-3")} />
      {!collapsed && <span className="flex-1 truncate">{item.label}</span>}
      {item.badge &&
        item.badge.count > 0 &&
        (collapsed ? (
          <span aria-hidden className={cn("absolute right-1.5 top-1.5 size-1.5 rounded-full", item.badge.tone === "danger" ? "bg-danger" : "bg-accent")} />
        ) : (
          <CountBadge tone={item.badge.tone}>{item.badge.count}</CountBadge>
        ))}
      {item.badge && item.badge.count > 0 && <span className="sr-only">, {item.badge.count} open</span>}
    </Link>
  );
  return collapsed ? (
    <Tooltip className="flex w-full" label={item.planned ? `${item.label} · planned` : item.label}>
      {row}
    </Tooltip>
  ) : (
    row
  );
}

function ApiIndicator({ collapsed }: { collapsed: boolean }) {
  const { apiStatus, environment, source } = useWorkspace();
  const dot = apiStatus === "connected" ? "bg-ok" : apiStatus === "offline" ? "bg-warn" : "animate-pulse bg-ink-4";
  const label = apiStatus === "connected" ? "API connected" : apiStatus === "offline" ? "API offline" : "Checking API";
  const detail = `${environment} · ${source === "api" ? "live tenants" : "demo data"}`;
  const body = (
    <span role="status" className={cn("flex items-center gap-2 px-1 text-[12px]", collapsed && "justify-center")}>
      <span aria-hidden className={cn("size-1.5 shrink-0 rounded-full", dot)} />
      {collapsed ? (
        <span className="sr-only">
          {label}, {detail}
        </span>
      ) : (
        <span className="min-w-0 truncate">
          <span className="font-medium text-ink-2">{label}</span>
          <span className="text-ink-4"> · {detail}</span>
        </span>
      )}
    </span>
  );
  return collapsed ? (
    <Tooltip className="flex w-full" label={`${label} · ${detail}`}>
      <span tabIndex={0} className={cn("flex w-full justify-center rounded-sm py-1", ring)}>
        {body}
      </span>
    </Tooltip>
  ) : (
    body
  );
}
