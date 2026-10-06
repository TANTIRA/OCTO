"use client";

import { useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useFormat } from "@/lib/use-format";
import { useAlerts, useRules } from "@/lib/data/queries";
import { DEMO_NOW, SEVERITY_ORDER, hrefFor, type Alert, type AlertState, type Rule } from "@/lib/demo";
import { PageBody, PageHeader } from "@/components/page/page-header";
import { DataTable, type Column } from "@/components/data/data-table";
import { encodeView } from "@/components/data/table-state";
import { Button, LinkButton } from "@/components/ui/button";
import { EntityChip, StatusBadge } from "@/components/ui/badge";
import { Segmented, Switch, Tabs } from "@/components/ui/controls";
import { Sheet } from "@/components/ui/overlay";
import { FreshnessBadge, InlineAlert, useToast } from "@/components/feedback";
import { ObjectMetadata } from "@/components/object/object";
import { DecisionPanel, SeverityBadge, WorkflowStatus } from "@/components/workflow/workflow";
import { useBreadcrumb } from "@/components/shell/shell-context";

type Tab = "inbox" | "rules";
const now = new Date(DEMO_NOW);

/**
 * Alerts & rules (plan §18): alert inbox with acknowledge / assign / snooze /
 * resolve / open object / open rule, and the rule catalogue with definition,
 * conditions, sources, backtest, last trigger, affected entities, activation
 * and version.
 */
export function AlertsView() {
  const f = useFormat();
  const router = useRouter();
  const params = useSearchParams();
  const toast = useToast();
  useBreadcrumb(null);
  const alertsQ = useAlerts();
  const rulesQ = useRules();
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [rules, setRules] = useState<Rule[]>([]);
  useEffect(() => void (alertsQ.data && setAlerts(alertsQ.data)), [alertsQ.data]);
  useEffect(() => void (rulesQ.data && setRules(rulesQ.data)), [rulesQ.data]);

  const tab = (params.get("tab") === "rules" ? "rules" : "inbox") as Tab;
  const alertId = params.get("id");
  const ruleId = params.get("rule");
  const openAlert = alerts.find((a) => a.id === alertId) ?? null;
  const openRule = rules.find((r) => r.id === ruleId) ?? null;
  const nav = (q: string) => router.replace(`/app/alerts${q ? `?${q}` : ""}`, { scroll: false });

  const update = (ids: string[], patch: Partial<Alert>, verb: string) => {
    setAlerts((xs) => xs.map((a) => (ids.includes(a.id) ? { ...a, ...patch } : a)));
    toast({ tone: "ok", title: `${ids.length} alert${ids.length > 1 ? "s" : ""} ${verb}`, body: "Recorded in this session only (demo)." });
  };

  const alertCols: Column<Alert>[] = [
    { id: "title", header: "Alert", width: 300, hideable: false, value: (a) => a.title, cell: (a) => <span className="font-medium text-ink">{a.title}</span> },
    { id: "severity", header: "Severity", value: (a) => a.severity, sortValue: (a) => SEVERITY_ORDER[a.severity], facet: true, cell: (a) => <SeverityBadge severity={a.severity} /> },
    { id: "state", header: "State", value: (a) => a.state, facet: true, groupable: true, cell: (a) => <WorkflowStatus state={a.state} /> },
    { id: "entity", header: "Entity", value: (a) => a.entity.name, cell: (a) => <EntityChip type={a.entity.type} name={a.entity.name} href={hrefFor(a.entity)} /> },
    { id: "rule", header: "Rule", value: (a) => a.ruleId, facet: true, cell: (a) => <span className="font-data text-[12px]">{a.ruleId}</span> },
    { id: "observed", header: "Observed", value: (a) => a.observed, align: "right" },
    { id: "owner", header: "Owner", value: (a) => a.owner, facet: true },
    { id: "triggered", kind: "date", header: "Date", value: (a) => a.triggeredAt, facet: true, facetValue: (a) => { const h = (now.getTime() - new Date(a.triggeredAt).getTime()) / 3600_000; return h < 24 ? "Last 24 hours" : h < 168 ? "This week" : "Older"; }, cell: (a) => f.ago(a.triggeredAt, now) },
    { id: "age", header: "Age", value: (a) => now.getTime() - new Date(a.triggeredAt).getTime(), align: "right", defaultHidden: true, cell: (a) => `${Math.round((now.getTime() - new Date(a.triggeredAt).getTime()) / 3600_000)}h` },
  ];

  const ruleCols: Column<Rule>[] = [
    { id: "name", header: "Rule", width: 240, hideable: false, value: (r) => r.name, cell: (r) => <span className="font-medium text-ink">{r.name} <span className="font-data text-[11px] text-ink-4">{r.id}</span></span> },
    { id: "category", header: "Category", value: (r) => r.category, facet: true, groupable: true },
    { id: "severity", header: "Severity", value: (r) => r.severity, sortValue: (r) => SEVERITY_ORDER[r.severity], cell: (r) => <SeverityBadge severity={r.severity} /> },
    { id: "active", header: "State", value: (r) => (r.active ? "Active" : "Inactive"), facet: true, cell: (r) => <StatusBadge tone={r.active ? "ok" : "neutral"}>{r.active ? "Active" : "Inactive"}</StatusBadge> },
    { id: "affected", header: "Affected", value: (r) => r.affected, align: "right" },
    { id: "precision", header: "Backtest precision", value: (r) => r.backtest.precision, align: "right", cell: (r) => f.pct(r.backtest.precision, 0) },
    { id: "last", header: "Last trigger", value: (r) => r.lastTrigger ?? "", align: "right", cell: (r) => (r.lastTrigger ? f.ago(r.lastTrigger, now) : "—") },
    { id: "version", header: "Version", value: (r) => r.version, align: "right", cell: (r) => `v${r.version}` },
    { id: "owner", header: "Owner", value: (r) => r.owner },
  ];

  return (
    <>
      <PageHeader
        variant="list"
        eyebrow="Operate"
        title="Alerts"
        description="Rule and signal breaches across funds, companies and data sources."
        meta={
          <>
            <FreshnessBadge state="demo" asOf={`evaluated ${f.time(DEMO_NOW)}`} />
            <span className="text-[12px] text-ink-3">
              {alerts.filter((a) => a.state === "Open").length} open · {alerts.filter((a) => a.severity === "critical" && a.state !== "Resolved").length} critical
            </span>
          </>
        }
        tabs={<Tabs<Tab> label="Alerts sections" value={tab} onChange={(t) => nav(t === "rules" ? "tab=rules" : "")} className="border-b-0" items={[{ value: "inbox", label: "Inbox", count: alerts.filter((a) => a.state !== "Resolved").length }, { value: "rules", label: "Rules", count: rules.length }]} />}
      />
      <PageBody>
        {tab === "inbox" ? (
          <DataTable
            id="alerts"
            label="Alerts"
            data={alerts}
            status={alertsQ.isLoading ? "loading" : "ready"}
            columns={alertCols}
            rowId={(a) => a.id}
            selectable
            demo
            activeRowId={alertId}
            onRowOpen={(a) => nav(`id=${a.id}`)}
            exportName="octo-alerts"
            views={[
              { id: "open", name: "Needs action", state: { facets: { state: ["Open", "Acknowledged"] }, sort: [{ id: "severity", desc: false }] } },
              { id: "all", name: "All alerts", state: { sort: [{ id: "triggered", desc: true }] } },
              { id: "rule", name: "By rule", state: { groupBy: "rule" } },
            ]}
            rowActions={(a) => [
              { label: "Acknowledge", disabled: a.state !== "Open", onSelect: () => update([a.id], { state: "Acknowledged" }, "acknowledged") },
              { label: "Assign to me", onSelect: () => update([a.id], { owner: "You" }, "assigned to you") },
              { label: "Snooze 3 days", onSelect: () => update([a.id], { state: "Snoozed" }, "snoozed") },
              "separator",
              { label: `Open ${a.entity.type.toLowerCase()}`, onSelect: () => router.push(hrefFor(a.entity) ?? "/app") },
              { label: "Open rule", onSelect: () => nav(`tab=rules&rule=${a.ruleId}`) },
            ]}
            bulkActions={(sel, clear) => (
              <>
                <Button size="sm" onClick={() => (update(sel.map((a) => a.id), { state: "Acknowledged" }, "acknowledged"), clear())}>
                  Acknowledge
                </Button>
                <Button size="sm" onClick={() => (update(sel.map((a) => a.id), { owner: "You" }, "assigned to you"), clear())}>
                  Assign to me
                </Button>
                <Button size="sm" onClick={() => (update(sel.map((a) => a.id), { state: "Snoozed" }, "snoozed"), clear())}>
                  Snooze
                </Button>
              </>
            )}
            renderExpanded={(a) => (
              <p className="text-[12px] text-ink-3">
                Observed <span className="font-medium tabular-nums text-ink">{a.observed}</span> against <span className="font-medium tabular-nums text-ink">{a.threshold}</span> · source {a.source}
              </p>
            )}
            empty={{ title: "No alerts", body: "Alerts appear when a rule or news match fires for something in your portfolio." }}
          />
        ) : (
          <DataTable
            id="rules"
            label="Alert rules"
            data={rules}
            status={rulesQ.isLoading ? "loading" : "ready"}
            columns={ruleCols}
            rowId={(r) => r.id}
            activeRowId={ruleId}
            onRowOpen={(r) => nav(`tab=rules&rule=${r.id}`)}
            empty={{ title: "No rules", body: "Rules turn policies and covenants into alerts." }}
          />
        )}
      </PageBody>

      <Sheet open={!!openAlert} onClose={() => nav("")} eyebrow={openAlert?.id} title={openAlert?.title ?? ""}>
        {openAlert && <AlertDetail alert={openAlert} rule={rules.find((r) => r.id === openAlert.ruleId)} onUpdate={(p, verb) => update([openAlert.id], p, verb)} onOpenRule={() => nav(`tab=rules&rule=${openAlert.ruleId}`)} />}
      </Sheet>

      <Sheet open={!!openRule} onClose={() => nav("tab=rules")} eyebrow={openRule ? `Rule · ${openRule.id} · v${openRule.version}` : ""} title={openRule?.name ?? ""}>
        {openRule && (
          <div className="space-y-5">
            <div className="flex flex-wrap items-center gap-3">
              <SeverityBadge severity={openRule.severity} />
              <StatusBadge tone="neutral">{openRule.category}</StatusBadge>
              <label className="ml-auto flex items-center gap-2 text-[13px] text-ink-2">
                <Switch
                  checked={openRule.active}
                  onChange={(v) => {
                    setRules((xs) => xs.map((r) => (r.id === openRule.id ? { ...r, active: v } : r)));
                    toast({ tone: "ok", title: `${openRule.id} ${v ? "activated" : "deactivated"}`, body: "Change recorded as a new rule version in the audit trail (demo)." });
                  }}
                  label={`${openRule.name} active`}
                />
                {openRule.active ? "Active" : "Inactive"}
              </label>
            </div>
            <p className="text-[13px] leading-relaxed text-ink-2">{openRule.definition}</p>
            <section>
              <h3 className="text-[12px] font-medium text-ink-3">Conditions</h3>
              <ul className="mt-1.5 space-y-1">
                {openRule.conditions.map((c) => (
                  <li key={c} className="rounded-md border border-line bg-subtle px-3 py-1.5 font-data text-[12px] text-ink">
                    {c}
                  </li>
                ))}
              </ul>
            </section>
            <ObjectMetadata
              items={[
                { label: "Source dependencies", value: openRule.sources.join(", ") },
                { label: "Last trigger", value: openRule.lastTrigger ? `${f.dateTime(openRule.lastTrigger)} UTC` : "Never" },
                { label: "Affected entities", value: openRule.affected },
                { label: "Owner", value: openRule.owner },
                { label: "Backtest", value: `${openRule.backtest.triggers} triggers · ${openRule.backtest.period}` },
                { label: "Backtest precision", value: f.pct(openRule.backtest.precision, 0) },
              ]}
            />
            {openRule.backtest.precision < 80 && <InlineAlert tone="warn">Precision is below 80% in backtest; expect false positives. Consider tightening the conditions.</InlineAlert>}
            <LinkButton size="sm" href={`/app/alerts?alerts=${encodeView({ query: "", sort: [{ id: "triggered", desc: true }], facets: { rule: [openRule.id] }, hidden: ["age"], order: [], pinned: ["title"], groupBy: null })}`}>
              Show alerts from this rule
            </LinkButton>
          </div>
        )}
      </Sheet>
    </>
  );
}

function AlertDetail({ alert, rule, onUpdate, onOpenRule }: { alert: Alert; rule?: Rule; onUpdate: (p: Partial<Alert>, verb: string) => void; onOpenRule: () => void }) {
  const f = useFormat();
  const [snooze, setSnooze] = useState<"1" | "3" | "7">("3");
  const [resolving, setResolving] = useState(false);
  const closed = alert.state === "Resolved";
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center gap-2">
        <SeverityBadge severity={alert.severity} />
        <WorkflowStatus state={alert.state as AlertState} />
        <EntityChip type={alert.entity.type} name={alert.entity.name} href={hrefFor(alert.entity)} />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div className="rounded-lg border border-line p-3">
          <p className="text-[12px] text-ink-3">Observed</p>
          <p className="mt-1 text-metric font-semibold tabular-nums text-danger">{alert.observed}</p>
        </div>
        <div className="rounded-lg border border-line p-3">
          <p className="text-[12px] text-ink-3">Threshold</p>
          <p className="mt-1 text-metric font-semibold tabular-nums text-ink">{alert.threshold}</p>
        </div>
      </div>
      <ObjectMetadata
        items={[
          { label: "Rule", value: <button type="button" onClick={onOpenRule} className="cursor-pointer text-accent hover:underline">{rule ? `${rule.name} (${rule.id} v${rule.version})` : alert.ruleId}</button> },
          { label: "Source", value: alert.source },
          { label: "Owner", value: alert.owner },
          { label: "Triggered", value: `${f.dateTime(alert.triggeredAt)} UTC` },
        ]}
      />
      {!closed && (
        <div className="space-y-4 rounded-lg border border-line p-4">
          <div className="flex flex-wrap gap-2">
            {alert.state === "Open" && <Button size="sm" onClick={() => onUpdate({ state: "Acknowledged" }, "acknowledged")}>Acknowledge</Button>}
            {alert.owner !== "You" && <Button size="sm" onClick={() => onUpdate({ owner: "You" }, "assigned to you")}>Assign to me</Button>}
            <LinkButton size="sm" href={hrefFor(alert.entity) ?? "/app"}>Open {alert.entity.type.toLowerCase()}</LinkButton>
          </div>
          <div className="flex flex-wrap items-center gap-2 text-[12px] text-ink-3">
            Snooze for
            <Segmented size="sm" label="Snooze duration" value={snooze} onChange={setSnooze} items={[{ value: "1", label: "1 day" }, { value: "3", label: "3 days" }, { value: "7", label: "7 days" }]} />
            <Button size="sm" onClick={() => onUpdate({ state: "Snoozed" }, `snoozed for ${snooze} day${snooze === "1" ? "" : "s"}`)}>Snooze</Button>
          </div>
          {resolving ? (
            <DecisionPanel idPrefix={`alr-${alert.id}`} noteLabel="Resolution note" hint="Required. Explain what was done; it is kept in the audit trail." decisions={[{ id: "resolve", label: "Resolve alert", variant: "primary" }]} onDecide={(_, note) => onUpdate({ state: "Resolved" }, `resolved — “${note.slice(0, 40)}”`)} />
          ) : (
            <Button size="sm" variant="primary" onClick={() => setResolving(true)}>
              Resolve…
            </Button>
          )}
        </div>
      )}
      {closed && <p className="rounded-lg bg-ok/10 px-3 py-2 text-[12px] text-ink-2">Resolved alerts are read-only. The rule re-opens a new alert if it fires again.</p>}
    </div>
  );
}
