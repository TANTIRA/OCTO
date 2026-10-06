"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Bell, Briefcase, Building2, Clock, CornerDownLeft, FilePlus2, FileText, Layers, ListPlus, ListTodo, PanelLeft, RefreshCw, Rows3, Search, Settings, Sparkles, Users } from "lucide-react";
import { cn } from "@/lib/utils";
import { ALERTS, APPROVALS, COMPANIES, FUNDS, INVESTMENTS, TASKS, companyById, fundById } from "@/lib/demo";
import { usePreferences } from "@/lib/preferences";
import { useWorkspace } from "@/lib/workspace";
import { usePlatformAdmin } from "@/lib/use-tenants";
import { Kbd } from "@/components/ui/badge";
import { useToast } from "@/components/feedback";
import { NAV_ITEMS } from "./nav-config";
import { useShell } from "./shell-context";

type Group = "Recent" | "Pages" | "Records" | "Actions" | "Workspaces" | "Ask OCTO";
type Command = { id: string; group: Group; label: string; hint?: string; icon: React.ReactNode; keywords?: string; disabled?: boolean; run?: () => void };

const ORDER: Group[] = ["Recent", "Pages", "Records", "Actions", "Workspaces", "Ask OCTO"];

/**
 * Command menu (plan FE-SHELL-004, §22). One combobox for navigation, entity
 * search (funds, companies, investments, documents, workflows, alerts),
 * recent objects, and actions. Ask OCTO is shown as planned, not faked.
 */
export function CommandMenu({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  if (!open || !mounted) return null;
  return createPortal(<Palette onClose={onClose} />, document.querySelector(".octo-app") ?? document.body);
}

function Palette({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const qc = useQueryClient();
  const toast = useToast();
  const { recent } = useShell();
  const { density, setDensity, sidebarCollapsed, setSidebarCollapsed } = usePreferences();
  const { workspaces, current, setCurrent } = useWorkspace();
  const platformAdmin = usePlatformAdmin();
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLUListElement>(null);

  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    inputRef.current?.focus();
    return () => prev?.focus?.({ preventScroll: true });
  }, []);

  const commands = useMemo<Command[]>(() => {
    const go = (href: string) => () => router.push(href);
    const records: Command[] = [
      ...FUNDS.map((f) => ({ id: f.id, group: "Records" as const, label: f.name, hint: `Fund · ${f.strategy} · ${f.vintage}`, icon: <Layers />, run: go(`/app/funds/${f.slug}`) })),
      ...COMPANIES.map((c) => ({ id: c.id, group: "Records" as const, label: c.name, hint: `Company · ${c.sector} · ${c.status}`, keywords: `${c.geography} ${c.owner}`, icon: <Building2 />, run: go(`/app/companies/${c.id.toLowerCase()}`) })),
      ...INVESTMENTS.map((i) => ({
        id: i.id,
        group: "Records" as const,
        label: `${companyById(i.companyId)?.name} · ${fundById(i.fundId)?.short}`,
        hint: `Investment · ${i.instrument}`,
        keywords: i.id,
        icon: <Briefcase />,
        run: go(`/app/investments?focus=${i.id}`),
      })),
      // Documents (V2 TOP-003): the vault documents each company dossier exposes.
      ...COMPANIES.filter((c) => c.status !== "Exited").flatMap((c) =>
        ["Q3 management accounts", "Q3 compliance certificate", "Board pack — September"].map((d, k) => ({
          id: `${c.id}-DOC-${k + 1}`,
          group: "Records" as const,
          label: `${d} · ${c.name}`,
          hint: "Document",
          keywords: "document file pdf vault",
          icon: <FileText />,
          run: go(`/app/companies/${c.id.toLowerCase()}?tab=documents`),
        })),
      ),
      ...TASKS.map((t) => ({ id: t.id, group: "Records" as const, label: t.title, hint: `Task · ${t.status}`, icon: <ListTodo />, run: go(`/app/workflows?tab=tasks`) })),
      ...APPROVALS.map((a) => ({ id: a.id, group: "Records" as const, label: a.title, hint: `Approval · due ${a.due}`, icon: <ListTodo />, run: go(`/app/workflows?tab=approvals&id=${a.id}`) })),
      ...ALERTS.map((a) => ({ id: a.id, group: "Records" as const, label: a.title, hint: `Alert · ${a.severity} · ${a.state}`, keywords: a.entity.name, icon: <Bell />, run: go(`/app/alerts?id=${a.id}`) })),
    ];
    return [
      ...recent.map((r) => ({ id: `recent-${r.href}`, group: "Recent" as const, label: r.name, hint: r.type, icon: <Clock />, run: go(r.href) })),
      ...NAV_ITEMS.filter((n) => !n.adminOnly || platformAdmin === true).map((n) => ({ id: `nav-${n.id}`, group: "Pages" as const, label: n.label, icon: <n.icon />, keywords: "go to page", run: go(n.href) })),
      ...records,
      { id: "act-task", group: "Actions", label: "Create task", icon: <ListPlus />, run: go("/app/workflows?tab=tasks&new=1") },
      { id: "act-report", group: "Actions", label: "Create report", icon: <FilePlus2 />, run: go("/app/reports") },
      {
        id: "act-refresh",
        group: "Actions",
        label: "Refresh data",
        keywords: "reload sync",
        icon: <RefreshCw />,
        run: () => {
          qc.invalidateQueries();
          toast({ tone: "ok", title: "Refreshing data", body: "Every panel reloads from its source." });
        },
      },
      { id: "act-density", group: "Actions", label: density === "compact" ? "Switch to comfortable density" : "Switch to compact density", keywords: "rows table", icon: <Rows3 />, run: () => setDensity(density === "compact" ? "comfortable" : "compact") },
      { id: "act-sidebar", group: "Actions", label: sidebarCollapsed ? "Expand sidebar" : "Collapse sidebar", icon: <PanelLeft />, run: () => setSidebarCollapsed(!sidebarCollapsed) },
      { id: "act-settings", group: "Actions", label: "Open settings", icon: <Settings />, run: go("/app/settings") },
      { id: "act-approvals", group: "Actions", label: "Review pending approvals", icon: <ArrowRight />, run: go("/app/workflows?tab=approvals") },
      ...workspaces.map((w) => ({ id: `ws-${w.slug}`, group: "Workspaces" as const, label: `Switch to ${w.name}`, hint: w.slug === current.slug ? "Current" : w.detail, icon: <Users />, disabled: w.slug === current.slug, run: () => setCurrent(w.slug) })),
      { id: "ask", group: "Ask OCTO", label: "Ask OCTO a question about your portfolio", hint: "Planned", icon: <Sparkles />, disabled: true },
    ];
  }, [router, qc, toast, recent, density, setDensity, sidebarCollapsed, setSidebarCollapsed, workspaces, current.slug, setCurrent, platformAdmin]);

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    const hits = q ? commands.filter((c) => `${c.label} ${c.hint ?? ""} ${c.keywords ?? ""} ${c.group} ${c.id}`.toLowerCase().includes(q)) : commands.filter((c) => c.group !== "Records");
    return ORDER.flatMap((g) => hits.filter((c) => c.group === g).slice(0, q ? 8 : g === "Pages" ? 20 : 6));
  }, [commands, query]);

  useEffect(() => setActive(results.findIndex((c) => !c.disabled)), [query]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    listRef.current?.querySelector(`[data-index="${active}"]`)?.scrollIntoView({ block: "nearest" });
  }, [active]);

  const run = (c?: Command) => {
    if (!c || c.disabled || !c.run) return;
    onClose();
    c.run();
  };
  const move = (dir: 1 | -1) => {
    if (!results.some((c) => !c.disabled)) return;
    let i = active;
    do i = (i + dir + results.length) % results.length;
    while (results[i].disabled);
    setActive(i);
  };
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") (e.preventDefault(), move(1));
    else if (e.key === "ArrowUp") (e.preventDefault(), move(-1));
    else if (e.key === "Home") (e.preventDefault(), setActive(results.findIndex((c) => !c.disabled)));
    else if (e.key === "End") (e.preventDefault(), setActive(results.length - 1 - [...results].reverse().findIndex((c) => !c.disabled)));
    else if (e.key === "Enter") (e.preventDefault(), run(results[active]));
    else if (e.key === "Escape") (e.preventDefault(), onClose());
    else if (e.key === "Tab") e.preventDefault();
  };

  let lastGroup = "";
  return (
    <div className="fixed inset-0 z-[80] flex items-start justify-center px-3 pt-[12vh]">
      <div aria-hidden className="absolute inset-0 bg-black/40 motion-safe:animate-[fade-in_140ms_ease-out]" onClick={onClose} />
      <div role="dialog" aria-modal="true" aria-label="Search and commands" className="relative flex max-h-[min(34rem,76vh)] w-full max-w-xl flex-col overflow-hidden rounded-lg border border-line bg-raised text-ink shadow-dialog motion-safe:animate-[pop-in_160ms_var(--ease-out-soft)]">
        <div className="flex items-center gap-2.5 border-b border-line px-4">
          <Search aria-hidden className="size-4 shrink-0 text-ink-3" />
          <input
            ref={inputRef}
            role="combobox"
            aria-expanded="true"
            aria-controls="command-list"
            aria-activedescendant={active >= 0 && results[active] ? `cmd-${results[active].id}` : undefined}
            aria-autocomplete="list"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onKeyDown}
            placeholder="Search entities, pages, reports, workflows, documents…"
            className="h-12 flex-1 bg-transparent text-[14px] text-ink placeholder:text-ink-4 focus:outline-none"
          />
          <Kbd>Esc</Kbd>
        </div>
        <ul id="command-list" ref={listRef} role="listbox" aria-label="Results" className="min-h-0 flex-1 overflow-y-auto p-1.5">
          {results.length === 0 && <li className="px-3 py-8 text-center text-[13px] text-ink-3">No results for “{query}”. Try a fund, company, deal or page.</li>}
          {results.map((c, i) => {
            const heading = c.group !== lastGroup ? c.group : null;
            lastGroup = c.group;
            return (
              <li key={c.id} role="presentation">
                {heading && <p className="px-2.5 pb-1 pt-2.5 text-label uppercase text-ink-4">{heading}</p>}
                <div
                  id={`cmd-${c.id}`}
                  data-index={i}
                  role="option"
                  aria-selected={i === active}
                  aria-disabled={c.disabled || undefined}
                  onMouseMove={() => !c.disabled && setActive(i)}
                  onClick={() => run(c)}
                  className={cn("flex h-9 items-center gap-2.5 rounded-md px-2.5 text-[13px] [&_svg]:size-4 [&_svg]:shrink-0", c.disabled ? "cursor-default text-ink-4" : "cursor-pointer text-ink-2", i === active && "bg-hover text-ink")}
                >
                  <span className={cn(i === active ? "text-accent" : "text-ink-3", c.disabled && "text-ink-4")}>{c.icon}</span>
                  <span className="min-w-0 flex-1 truncate">{c.label}</span>
                  {c.hint && <span className="max-w-[45%] truncate text-[12px] text-ink-4">{c.hint}</span>}
                  {i === active && <CornerDownLeft aria-hidden className="text-ink-3" />}
                </div>
              </li>
            );
          })}
        </ul>
        <div className="flex items-center gap-4 border-t border-line px-4 py-2 text-[11px] text-ink-4">
          <span className="flex items-center gap-1">
            <Kbd>↑</Kbd>
            <Kbd>↓</Kbd> move
          </span>
          <span className="flex items-center gap-1">
            <Kbd>↵</Kbd> open
          </span>
          <span className="ml-auto">Records are demo data</span>
        </div>
      </div>
    </div>
  );
}
