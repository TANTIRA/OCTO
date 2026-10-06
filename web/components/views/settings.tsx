"use client";

import { useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Check, Copy, KeyRound } from "lucide-react";
import { cn } from "@/lib/utils";
import { useFormat } from "@/lib/use-format";
import { usePreferences, type Density, type Locale, type Theme } from "@/lib/preferences";
import { useWorkspace } from "@/lib/workspace";
import { AUDIT, DEMO_NOW, ROLES, SOURCES, USERS } from "@/lib/demo";
import { PageBody, PageHeader } from "@/components/page/page-header";
import { Panel, PanelBody, PanelHead } from "@/components/page/panel";
import { DataTable } from "@/components/data/data-table";
import { Button, ring, ringInset } from "@/components/ui/button";
import { Kbd, StatusBadge } from "@/components/ui/badge";
import { Field, Input, Segmented, Select, Switch } from "@/components/ui/controls";
import { InlineAlert, useToast } from "@/components/feedback";
import { SHORTCUTS } from "@/components/shell/shortcuts";
import { useBreadcrumb } from "@/components/shell/shell-context";

const GROUPS = [
  { label: "Personal", items: [["profile", "Profile"], ["appearance", "Display"], ["notifications", "Notifications"], ["shortcuts", "Shortcuts"]] },
  { label: "Workspace", items: [["workspace", "Workspace"], ["users", "Users"], ["roles", "Roles & permissions"], ["approvals", "Approvals"], ["integrations", "Integrations"], ["data", "Data"], ["security", "Security"], ["api", "API"], ["audit", "Audit log"]] },
  { label: "Governance", items: [["retention", "Retention"], ["classification", "Classification"], ["models", "Model governance"], ["ai", "AI policies"], ["report-policies", "Report policies"]] },
] as const;
type Section = (typeof GROUPS)[number]["items"][number][0];
const now = new Date(DEMO_NOW);

/**
 * Settings (plan §27): personal preferences separated from workspace
 * administration and governance (Mesta model), in Vestra's sectioned visual
 * language. Personal settings apply immediately; workspace changes are demo.
 */
export function SettingsView() {
  const params = useSearchParams();
  useBreadcrumb(null);
  const all = GROUPS.flatMap((g) => g.items.map((i) => i[0])) as Section[];
  const section = (all.includes(params.get("tab") as Section) ? params.get("tab") : "profile") as Section;
  const pairs: (readonly [string, string])[] = [];
  for (const g of GROUPS) for (const i of g.items) pairs.push(i);
  const label = pairs.find((i) => i[0] === section)?.[1] ?? "Settings";

  return (
    <>
      <PageHeader variant="settings" eyebrow="System" title="Settings" description="Your preferences, workspace administration and governance policies." />
      <PageBody>
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[220px_1fr]">
          <nav aria-label="Settings sections" className="lg:sticky lg:top-4 lg:self-start">
            {GROUPS.map((g) => (
              <div key={g.label} className="mb-4">
                <p className="px-2.5 pb-1.5 text-label uppercase text-ink-4">{g.label}</p>
                <ul className="space-y-0.5">
                  {g.items.map(([id, name]) => (
                    <li key={id}>
                      <Link
                        href={`/app/settings?tab=${id}`}
                        replace
                        scroll={false}
                        aria-current={section === id ? "page" : undefined}
                        className={cn("block rounded-md px-2.5 py-1.5 text-[13px] font-medium", section === id ? "bg-accent-soft text-accent-ink" : "text-ink-2 hover:bg-hover hover:text-ink", ringInset)}
                      >
                        {name}
                      </Link>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </nav>
          <div className="min-w-0 space-y-4" aria-label={label}>
            <SectionBody section={section} />
          </div>
        </div>
      </PageBody>
    </>
  );
}

function Row({ title, body, control }: { title: string; body?: string; control: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-2 border-t border-line py-3.5 first:border-t-0 first:pt-0 sm:flex-row sm:items-center sm:justify-between sm:gap-6">
      <div className="min-w-0">
        <p className="text-[13px] font-medium text-ink">{title}</p>
        {body && <p className="mt-0.5 text-[12px] text-ink-3">{body}</p>}
      </div>
      <div className="shrink-0">{control}</div>
    </div>
  );
}

function SectionBody({ section }: { section: Section }) {
  const f = useFormat();
  const toast = useToast();
  const prefs = usePreferences();
  const { current, apiStatus, source, environment } = useWorkspace();
  const [notif, setNotif] = useState({ critical: true, approvals: true, drafts: true, digest: false, email: true });
  const [gov, setGov] = useState({ aiApproval: true, selfHosted: true, citations: true, piiRedaction: true, retentionYears: "10" });
  const saved = (what: string) => toast({ tone: "ok", title: `${what} saved`, body: "Workspace settings are demo-only in this environment." });

  switch (section) {
    case "profile":
      return (
        <Panel>
          <PanelHead title="Profile" description="How you appear on approvals, comments and the audit trail" />
          <PanelBody className="grid gap-4 sm:grid-cols-2">
            <Field id="p-name" label="Display name">
              <Input id="p-name" defaultValue="Local session" />
            </Field>
            <Field id="p-email" label="Email" hint="Managed by your identity provider.">
              <Input id="p-email" defaultValue="—" disabled aria-describedby="p-email-hint" />
            </Field>
            <Field id="p-role" label="Title">
              <Input id="p-role" defaultValue="Investment director" />
            </Field>
            <Field id="p-tz" label="Time zone" hint="Dates are stored and shown in UTC; this only affects reminders.">
              <Select id="p-tz" defaultValue="Asia/Jakarta" aria-describedby="p-tz-hint">
                <option>Asia/Jakarta</option>
                <option>Asia/Singapore</option>
                <option>UTC</option>
              </Select>
            </Field>
            <div className="sm:col-span-2">
              <Button variant="primary" onClick={() => saved("Profile")}>
                Save profile
              </Button>
            </div>
          </PanelBody>
        </Panel>
      );
    case "appearance":
      return (
        <Panel>
          <PanelHead title="Display" description="Applies across every page, on this browser." />
          <PanelBody>
            <Row title="Theme" body="Light is the default. System follows this device's setting. Also in the top bar." control={<Segmented<Theme> label="Theme" value={prefs.theme} onChange={prefs.setTheme} items={[{ value: "light", label: "Light" }, { value: "dark", label: "Dark" }, { value: "system", label: "System" }]} />} />
            <Row title="Density" body="Compact rows are 56px; comfortable rows are 64px. Every table follows this." control={<Segmented<Density> label="Density" value={prefs.density} onChange={prefs.setDensity} items={[{ value: "compact", label: "Compact" }, { value: "comfortable", label: "Comfortable" }]} />} />
            <Row title="Number & date format" body={`Preview: ${f.money(812.4e6)} · ${f.pct(18.2)} · ${f.dateTime(DEMO_NOW)}`} control={<Segmented<Locale> label="Language" value={prefs.locale} onChange={prefs.setLocale} items={[{ value: "en", label: "English" }, { value: "id", label: "Bahasa Indonesia" }]} />} />
            <Row title="Collapsed sidebar" body="Toggle any time with [" control={<Switch checked={prefs.sidebarCollapsed} onChange={prefs.setSidebarCollapsed} label="Collapsed sidebar" />} />
          </PanelBody>
        </Panel>
      );
    case "notifications":
      return (
        <Panel>
          <PanelHead title="Notifications" description="What reaches you, and where" />
          <PanelBody>
            {([
              ["critical", "Critical alerts", "Covenant breaches, failed sources, and anything marked critical."],
              ["approvals", "Approvals waiting on me", "Investment memos, LP reports, capital calls and data overrides."],
              ["drafts", "AI drafts ready for review", "Variance explanations, briefings and memo sections."],
              ["digest", "Daily digest", "One summary at 07:00 instead of individual notifications."],
              ["email", "Email copies", "Also send in-app notifications by email."],
            ] as [keyof typeof notif, string, string][]).map(([k, t, b]) => (
              <Row key={k} title={t} body={b} control={<Switch checked={notif[k]} onChange={(v) => setNotif({ ...notif, [k]: v })} label={t} />} />
            ))}
          </PanelBody>
        </Panel>
      );
    case "shortcuts":
      return (
        <Panel>
          <PanelHead title="Keyboard shortcuts" description="Press ? anywhere to see these" />
          <PanelBody>
            <ul className="grid gap-x-8 sm:grid-cols-2">
              {SHORTCUTS.map((s) => (
                <li key={s.label} className="flex items-center justify-between gap-3 border-b border-line py-2 text-[13px] text-ink-2">
                  {s.label}
                  <span className="flex gap-1">
                    {s.keys.map((k) => (
                      <Kbd key={k}>{k}</Kbd>
                    ))}
                  </span>
                </li>
              ))}
            </ul>
          </PanelBody>
        </Panel>
      );
    case "workspace":
      return (
        <Panel>
          <PanelHead title="Workspace" />
          <PanelBody>
            <Row title="Name" control={<span className="text-[13px] font-medium text-ink">{current.name}</span>} />
            <Row title="Environment" control={<StatusBadge tone="info">{environment}</StatusBadge>} />
            <Row title="API" body={source === "api" ? "Tenants loaded from /api/v1/me/access" : "Using the labelled demo tenant list"} control={<StatusBadge tone={apiStatus === "connected" ? "ok" : "warn"}>{apiStatus === "connected" ? "Connected" : apiStatus === "checking" ? "Checking" : "Offline"}</StatusBadge>} />
            <Row title="Base currency" control={<span className="text-[13px] font-medium text-ink">USD</span>} />
            <Row title="Reporting calendar" control={<span className="text-[13px] font-medium text-ink">Calendar quarters · UTC</span>} />
          </PanelBody>
        </Panel>
      );
    case "users":
      return (
        <DataTable
          id="users"
          label="Users"
          data={USERS}
          rowId={(u) => u.id}
          demo
          columns={[
            { id: "name", header: "Name", width: 200, value: (u) => u.name, cell: (u) => <span className="font-medium text-ink">{u.name}</span> },
            { id: "email", header: "Email", value: (u) => u.email },
            { id: "role", header: "Role", value: (u) => u.role, facet: true },
            { id: "ws", header: "Workspaces", value: (u) => u.workspaces, align: "right" },
            { id: "mfa", header: "MFA", value: (u) => (u.mfa ? "On" : "Off"), facet: true, cell: (u) => <StatusBadge tone={u.mfa ? "ok" : "danger"}>{u.mfa ? "On" : "Off"}</StatusBadge> },
            { id: "active", header: "Last active", value: (u) => u.lastActive, align: "right", cell: (u) => f.ago(u.lastActive, now) },
          ]}
          rowActions={(u) => [
            { label: "Change role", onSelect: () => saved(`Role for ${u.name}`) },
            { label: "Require MFA", disabled: u.mfa, onSelect: () => saved(`MFA requirement for ${u.name}`) },
            "separator",
            { label: "Remove from workspace", danger: true, onSelect: () => toast({ tone: "info", title: "Removal needs confirmation", body: "Workspace admins confirm removals in the identity provider (demo)." }) },
          ]}
          empty={{ title: "No users", body: "" }}
        />
      );
    case "roles":
      return (
        <div className="grid gap-3 md:grid-cols-2">
          {ROLES.map((r) => (
            <Panel key={r.name}>
              <PanelHead title={r.name} toolbar={<StatusBadge tone="neutral">{r.members} member{r.members > 1 ? "s" : ""}</StatusBadge>} />
              <PanelBody className="pt-1">
                <ul className="space-y-1.5">
                  {r.permissions.map((p) => (
                    <li key={p} className="flex items-center gap-2 text-[13px] text-ink-2">
                      <Check aria-hidden className="size-3.5 text-ok" /> {p}
                    </li>
                  ))}
                </ul>
              </PanelBody>
            </Panel>
          ))}
        </div>
      );
    case "approvals":
      return (
        <Panel>
          <PanelHead title="Approval policies" description="Who must sign off, and how many" />
          <PanelBody>
            {[
              ["Investment committee memo", "3 of 3: deal team, risk, IC chair"],
              ["LP report", "CFO"],
              ["Capital call", "Fund accounting + CFO"],
              ["Data override", "Controller"],
              ["Valuation mark", "Valuation committee (2 of 3)"],
            ].map(([t, b]) => (
              <Row key={t} title={t} body={b} control={<Button size="sm" onClick={() => saved(`${t} policy`)}>Edit</Button>} />
            ))}
          </PanelBody>
        </Panel>
      );
    case "integrations":
      return (
        <div className="grid gap-3 md:grid-cols-2">
          {SOURCES.map((s) => (
            <Panel key={s.id}>
              <PanelBody className="flex items-center justify-between gap-3 py-3.5">
                <div>
                  <p className="text-[13px] font-medium text-ink">{s.name}</p>
                  <p className="text-[12px] text-ink-3">{s.kind}</p>
                </div>
                <StatusBadge tone={s.status === "Healthy" ? "ok" : s.status === "Failed" ? "danger" : "warn"}>{s.status}</StatusBadge>
              </PanelBody>
            </Panel>
          ))}
        </div>
      );
    case "data":
      return (
        <Panel>
          <PanelHead title="Data" />
          <PanelBody>
            <Row title="Valuation mark policy" body="Positions without an approved mark are flagged after this many days." control={<Select aria-label="Mark policy days" defaultValue="30" className="w-28"><option>30</option><option>45</option><option>60</option></Select>} />
            <Row title="Reconciliation tolerance" body="Breaks below this absolute variance auto-resolve as rounding." control={<Select aria-label="Tolerance" defaultValue="$100" className="w-28"><option>$10</option><option>$100</option><option>$1,000</option></Select>} />
            <Row title="Sources and mappings" control={<Link className="text-[13px] font-medium text-accent hover:underline" href="/app/data">Open Data & Sources</Link>} />
          </PanelBody>
        </Panel>
      );
    case "security":
      return (
        <Panel>
          <PanelHead title="Security" />
          <PanelBody>
            <Row title="Single sign-on" body="SAML via your identity provider" control={<StatusBadge tone="neutral">Not configured</StatusBadge>} />
            <Row title="Require MFA for all users" body={`${USERS.filter((u) => !u.mfa).length} user(s) currently without MFA`} control={<Switch checked={false} onChange={() => saved("MFA requirement")} label="Require MFA" />} />
            <Row title="Session timeout" control={<Select aria-label="Session timeout" defaultValue="8h" className="w-28"><option>1h</option><option>8h</option><option>24h</option></Select>} />
            <Row title="IP allow-list" body="Restrict access to office and VPN ranges" control={<Button size="sm" onClick={() => saved("IP allow-list")}>Manage</Button>} />
          </PanelBody>
        </Panel>
      );
    case "api":
      return (
        <Panel>
          <PanelHead title="API access" description="Tokens are shown once when created and never stored in the browser" />
          <PanelBody className="space-y-3">
            <InlineAlert tone="info">Create tokens in the secret manager-backed admin console. OCTO never displays an existing token.</InlineAlert>
            <Row title="Base URL" control={<code className="rounded-md border border-line bg-subtle px-2 py-1 font-data text-[12px]">/api/v1</code>} />
            <Row title="Personal access tokens" body="0 active" control={<Button size="sm" onClick={() => toast({ tone: "info", title: "Use the admin console", body: "Tokens are issued there (demo)." })}><KeyRound /> New token</Button>} />
            <Row title="OpenAPI spec" control={<Button size="sm" variant="ghost" onClick={() => toast({ tone: "ok", title: "Copied", body: "/api/v1/openapi.json" })}><Copy /> Copy URL</Button>} />
          </PanelBody>
        </Panel>
      );
    case "audit":
      return (
        <DataTable
          id="audit"
          label="Audit log"
          data={AUDIT}
          rowId={(a) => a.id}
          demo
          columns={[
            { id: "at", header: "When", width: 130, value: (a) => a.at, cell: (a) => f.ago(a.at, now) },
            { id: "actor", header: "Actor", value: (a) => a.actor, facet: true },
            { id: "action", header: "Action", value: (a) => a.action, facet: true },
            { id: "object", header: "Object", value: (a) => a.object },
            { id: "ip", header: "Source", value: (a) => a.ip, cell: (a) => <span className="font-data text-[12px]">{a.ip}</span> },
          ]}
          empty={{ title: "No audit events", body: "" }}
        />
      );
    case "retention":
      return (
        <Panel>
          <PanelHead title="Retention" />
          <PanelBody>
            <Row title="Financial records" body="Ledger, positions, valuations" control={<Select aria-label="Retention" value={gov.retentionYears} onChange={(e) => setGov({ ...gov, retentionYears: e.target.value })} className="w-32"><option value="7">7 years</option><option value="10">10 years</option><option value="15">15 years</option></Select>} />
            <Row title="Personal data" body="Contacts and user activity — UU PDP No. 27/2022" control={<span className="text-[13px] font-medium text-ink">Purpose-bound · reviewed yearly</span>} />
            <Row title="AI prompts and outputs" control={<span className="text-[13px] font-medium text-ink">2 years</span>} />
          </PanelBody>
        </Panel>
      );
    case "classification":
      return (
        <Panel>
          <PanelHead title="Data classification" />
          <PanelBody>
            {[
              ["Public", "Marketing content", "neutral"],
              ["Internal", "Process docs, templates", "info"],
              ["Confidential", "Financial data, LP information, deal materials", "warn"],
              ["Strictly confidential", "Personal data, credentials, IC deliberations", "danger"],
            ].map(([t, b, tone]) => (
              <Row key={t} title={t} body={b} control={<StatusBadge tone={tone as "neutral"}>{t}</StatusBadge>} />
            ))}
          </PanelBody>
        </Panel>
      );
    case "models":
      return (
        <Panel>
          <PanelHead title="Model governance" />
          <PanelBody>
            <Row title="octo-analyst v2.1" body="Variance explanations, briefings · self-hosted · eval pass 94%" control={<StatusBadge tone="ok">Approved</StatusBadge>} />
            <Row title="screening-model v3" body="Deal screening scores · eval pass 89%" control={<StatusBadge tone="ok">Approved</StatusBadge>} />
            <Row title="news-matcher v2" body="Entity matching for signals · precision 0.91" control={<StatusBadge tone="warn">Under review</StatusBadge>} />
          </PanelBody>
        </Panel>
      );
    case "ai":
      return (
        <Panel>
          <PanelHead title="AI policies" description="How generated content is governed" />
          <PanelBody>
            <Row title="Human approval for AI output" body="Drafts cannot be sent or applied without a person accepting them." control={<Switch checked={gov.aiApproval} onChange={(v) => setGov({ ...gov, aiApproval: v })} label="Human approval" />} />
            <Row title="Self-hosted models only" body="Confidential data never leaves approved infrastructure." control={<Switch checked={gov.selfHosted} onChange={(v) => setGov({ ...gov, selfHosted: v })} label="Self-hosted only" />} />
            <Row title="Citations required" body="AI claims must cite a source document or record." control={<Switch checked={gov.citations} onChange={(v) => setGov({ ...gov, citations: v })} label="Citations required" />} />
            <Row title="Redact personal data in prompts" control={<Switch checked={gov.piiRedaction} onChange={(v) => setGov({ ...gov, piiRedaction: v })} label="Redact personal data" />} />
            {!gov.aiApproval && <InlineAlert tone="danger">Turning off human approval lets AI output be applied without review. This change needs CTO sign-off.</InlineAlert>}
          </PanelBody>
        </Panel>
      );
    case "report-policies":
      return (
        <Panel>
          <PanelHead title="Report policies" />
          <PanelBody>
            <Row title="Only publish from reconciled data" body="Reports cannot publish while bound data has open high-severity breaks." control={<StatusBadge tone="ok">Enforced</StatusBadge>} />
            <Row title="Disclose open reconciliation items" control={<StatusBadge tone="ok">Enforced</StatusBadge>} />
            <Row title="Version every change" control={<StatusBadge tone="ok">Enforced</StatusBadge>} />
          </PanelBody>
        </Panel>
      );
  }
}
