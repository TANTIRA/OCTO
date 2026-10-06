"use client";

import { useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Plus } from "lucide-react";
import { useFormat } from "@/lib/use-format";
import { useAiDrafts, useApprovals, useExceptions, useTasks } from "@/lib/data/queries";
import { DEMO_NOW, SEVERITY_ORDER, hrefFor, type Approval, type ExceptionItem, type Severity, type Task } from "@/lib/demo";
import { PageBody, PageHeader } from "@/components/page/page-header";
import { Panel } from "@/components/page/panel";
import { DataTable, type Column } from "@/components/data/data-table";
import { Button, LinkButton } from "@/components/ui/button";
import { EntityChip, StatusBadge, Tag } from "@/components/ui/badge";
import { Field, Input, Select, Tabs } from "@/components/ui/controls";
import { ConfirmDialog, Sheet } from "@/components/ui/overlay";
import { EmptyState, FreshnessBadge, useToast } from "@/components/feedback";
import { AiDraftCard } from "@/components/ai/ai-draft";
import { DecisionPanel, SeverityBadge, WorkflowStatus, WorkflowStepper } from "@/components/workflow/workflow";
import { useBreadcrumb } from "@/components/shell/shell-context";

type Tab = "tasks" | "approvals" | "exceptions" | "ai";
const now = new Date(DEMO_NOW);

/**
 * Workflows (plan §17, §38 `/app/workflows`): my tasks, approvals with a
 * stepper and required comments, exceptions, and governed AI drafts. Every
 * item shows its state and one primary action.
 */
export function WorkflowsView() {
  const f = useFormat();
  const router = useRouter();
  const params = useSearchParams();
  const toast = useToast();
  useBreadcrumb(null);
  const tasksQ = useTasks();
  const approvalsQ = useApprovals();
  const exceptionsQ = useExceptions();
  const draftsQ = useAiDrafts();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [approvals, setApprovals] = useState<Approval[]>([]);
  const [draftDone, setDraftDone] = useState<Set<string>>(new Set());
  useEffect(() => void (tasksQ.data && setTasks(tasksQ.data)), [tasksQ.data]);
  useEffect(() => void (approvalsQ.data && setApprovals(approvalsQ.data)), [approvalsQ.data]);

  const tab = (["tasks", "approvals", "exceptions", "ai"].includes(params.get("tab") ?? "") ? params.get("tab") : "tasks") as Tab;
  const openId = params.get("id");
  const creating = params.get("new") === "1";
  const go = (t: Tab, extra = "") => router.replace(`/app/workflows?tab=${t}${extra}`, { scroll: false });
  const openApproval = tab === "approvals" ? approvals.find((a) => a.id === openId) ?? null : null;
  const drafts = (draftsQ.data ?? []).filter((d) => !draftDone.has(d.id));

  const taskCols: Column<Task>[] = [
    { id: "title", header: "Task", width: 300, hideable: false, value: (t) => t.title, cell: (t) => <span className="font-medium text-ink">{t.title}</span> },
    { id: "entity", header: "Linked to", value: (t) => t.entity.name, cell: (t) => <EntityChip type={t.entity.type} name={t.entity.name} href={hrefFor(t.entity)} /> },
    { id: "type", header: "Type", value: (t) => t.type, facet: true, groupable: true },
    { id: "priority", header: "Priority", value: (t) => t.priority, sortValue: (t) => SEVERITY_ORDER[t.priority], facet: true, cell: (t) => <SeverityBadge severity={t.priority} /> },
    { id: "status", header: "Status", value: (t) => t.status, facet: true, groupable: true, cell: (t) => <WorkflowStatus state={t.status} /> },
    { id: "assignee", header: "Assignee", value: (t) => t.assignee, facet: true },
    { id: "due", kind: "date", header: "Due", value: (t) => t.due, facet: true, facetValue: (t) => dueBucket(t.due) },
  ];

  const approvalCols: Column<Approval>[] = [
    { id: "title", header: "Approval", width: "30%", hideable: false, value: (a) => a.title, cell: (a) => <TitleCell title={a.title} sub={`${a.kind} · ${a.id}`} /> },
    { id: "entity", kind: "entity", header: "Linked to", width: "20%", value: (a) => a.entity.name, cell: (a) => <EntityChip type={a.entity.type} name={a.entity.name} href={hrefFor(a.entity)} /> },
    { id: "progress", kind: "status", header: "Progress", width: "16%", value: (a) => a.progress, cell: (a) => <StatusBadge tone="info">{a.progress}</StatusBadge> },
    { id: "requested", kind: "date", header: "Requested", width: "12%", value: (a) => a.requestedAt, cell: (a) => <span className="text-ink-3">{f.ago(a.requestedAt, now)}</span> },
    { id: "due", kind: "date", header: "Due", width: "10%", value: (a) => a.due, cell: (a) => <StatusBadge tone={a.due === "Today" ? "warn" : "neutral"} dot={a.due === "Today"}>{a.due}</StatusBadge> },
    { id: "action", kind: "action", header: "Action", width: "12%", sortable: false, value: () => "", cell: (a) => <Button size="sm" variant="primary" onClick={(e) => (e.stopPropagation(), go("approvals", `&id=${a.id}`))}>Review</Button> },
  ];

  const exceptionCols: Column<ExceptionItem>[] = [
    { id: "category", kind: "status", header: "Category", width: "14%", value: (e) => e.kind, facet: true, groupable: true, cell: (e) => <Tag tone={e.kind === "Covenant" ? "danger" : e.kind === "Recon break" ? "warn" : "neutral"}>{e.kind}</Tag> },
    { id: "severity", kind: "status", header: "Severity", width: "12%", value: (e) => e.severity, sortValue: (e) => SEVERITY_ORDER[e.severity], facet: true, cell: (e) => <SeverityBadge severity={e.severity} /> },
    { id: "headline", header: "Headline", width: "28%", hideable: false, value: (e) => e.title, cell: (e) => <span className="font-medium text-ink">{e.title}</span> },
    { id: "entity", kind: "entity", header: "Entity", width: "22%", value: (e) => e.entity.name, cell: (e) => <EntityChip type={e.entity.type} name={e.entity.name} href={hrefFor(e.entity)} /> },
    { id: "age", kind: "date", header: "Age", width: "9%", value: (e) => e.age, sortValue: (e) => ageHours(e.age), cell: (e) => <span className="tabular-nums text-ink-3">{e.age}</span> },
    { id: "action", kind: "action", header: "Action", width: "15%", sortable: false, value: () => "", cell: (e) => <LinkButton size="sm" href={e.href} onClick={(ev) => ev.stopPropagation()}>{e.action}</LinkButton> },
  ];

  const setStatus = (ids: string[], status: Task["status"]) => {
    setTasks((xs) => xs.map((t) => (ids.includes(t.id) ? { ...t, status } : t)));
    toast({ tone: "ok", title: `${ids.length} task${ids.length > 1 ? "s" : ""} → ${status}`, body: "Recorded in this session only (demo)." });
  };

  return (
    <>
      <PageHeader
        variant="workflow"
        eyebrow="Operate"
        title="Workflows"
        description="Everything waiting on a person: tasks, approvals, exceptions and AI drafts."
        meta={<FreshnessBadge state="demo" asOf={`queue as of ${f.time(DEMO_NOW)}`} />}
        actions={
          <Button variant="primary" onClick={() => go("tasks", "&new=1")}>
            <Plus /> New task
          </Button>
        }
        tabs={
          <Tabs<Tab>
            label="Workflow queues"
            value={tab}
            onChange={(t) => go(t)}
            variant="pill"
            className="pb-3"
            items={[
              { value: "tasks", label: "My tasks", count: tasks.filter((t) => t.assignee === "You" && t.status !== "Done").length },
              { value: "approvals", label: "Approvals", count: approvals.length },
              { value: "exceptions", label: "Exceptions", count: exceptionsQ.data?.length },
              { value: "ai", label: "AI drafts", count: drafts.length },
            ]}
          />
        }
      />
      <PageBody className="space-y-6">
        {tab === "tasks" && (
          <DataTable
            id="tasks"
            label="Tasks"
            data={tasks}
            status={tasksQ.isLoading ? "loading" : "ready"}
            columns={taskCols}
            rowId={(t) => t.id}
            selectable
            demo
            initial={{ sort: [{ id: "priority", desc: false }] }}
            views={[
              { id: "mine", name: "Assigned to me", state: { facets: { assignee: ["You"], status: ["To do", "In progress", "Blocked"] }, sort: [{ id: "priority", desc: false }] } },
              { id: "all", name: "All tasks", state: { sort: [{ id: "priority", desc: false }] } },
              { id: "status", name: "By status", state: { groupBy: "status" } },
            ]}
            rowActions={(t) => [
              { label: "Mark in progress", onSelect: () => setStatus([t.id], "In progress") },
              { label: "Mark done", onSelect: () => setStatus([t.id], "Done") },
              { label: "Mark blocked", onSelect: () => setStatus([t.id], "Blocked") },
              "separator",
              { label: `Open ${t.entity.type.toLowerCase()}`, onSelect: () => router.push(hrefFor(t.entity) ?? "/app") },
            ]}
            bulkActions={(sel, clear) => (
              <>
                <Button size="sm" onClick={() => (setStatus(sel.map((t) => t.id), "In progress"), clear())}>
                  Start
                </Button>
                <Button size="sm" variant="primary" onClick={() => (setStatus(sel.map((t) => t.id), "Done"), clear())}>
                  Mark done
                </Button>
              </>
            )}
            empty={{ title: "No tasks", body: "Tasks are created from approvals, alerts, recon breaks, or by hand." }}
          />
        )}

        {tab === "approvals" && (
          <DataTable
            id="approvals"
            label="Approvals"
            data={approvals}
            status={approvalsQ.isLoading ? "loading" : "ready"}
            columns={approvalCols}
            rowId={(a) => a.id}
            demo
            onRowOpen={(a) => go("approvals", `&id=${a.id}`)}
            activeRowId={openApproval?.id ?? null}
            empty={{ title: "No approvals waiting", body: "Investment memos, LP reports, capital calls and data overrides that need your sign-off appear here." }}
          />
        )}

        {tab === "exceptions" && (
          <DataTable
            id="exceptions"
            label="Exceptions"
            data={exceptionsQ.data ?? []}
            status={exceptionsQ.isLoading ? "loading" : "ready"}
            columns={exceptionCols}
            rowId={(e) => e.id}
            demo
            initial={{ sort: [{ id: "severity", desc: false }] }}
            onRowOpen={(e) => router.push(e.href)}
            empty={{ title: "No exceptions", body: "Covenant issues, stale valuations, recon breaks and overdue approvals appear here." }}
          />
        )}

        {tab === "ai" && (
          <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
            {drafts.length === 0 && (
              <Panel className="xl:col-span-2">
                <EmptyState title="No drafts waiting" body="AI drafts (variance explanations, briefings, memo sections) appear here for human review." />
              </Panel>
            )}
            {drafts.map((d) => (
              <div key={d.id}>
                <AiDraftCard
                  className="h-full"
                  draft={d}
                  onResolve={(o) => {
                    setDraftDone((s) => new Set(s).add(d.id));
                    toast({ tone: o === "rejected" ? "info" : "ok", title: { accepted: "Draft accepted", edited: "Edited draft accepted", "evidence-requested": "Evidence requested", rejected: "Draft rejected" }[o], body: "Recorded in this session only (demo)." });
                  }}
                />
              </div>
            ))}
          </div>
        )}
      </PageBody>

      <Sheet open={!!openApproval} onClose={() => go("approvals")} eyebrow={openApproval ? `${openApproval.kind} · ${openApproval.id}` : ""} title={openApproval?.title ?? ""}>
        {openApproval && (
          <div className="space-y-5">
            <div className="flex flex-wrap items-center gap-2">
              <EntityChip type={openApproval.entity.type} name={openApproval.entity.name} href={hrefFor(openApproval.entity)} />
              <StatusBadge tone={openApproval.due === "Today" ? "warn" : "neutral"}>Due {openApproval.due}</StatusBadge>
              <StatusBadge tone="info">{openApproval.progress}</StatusBadge>
            </div>
            <WorkflowStepper steps={openApproval.steps} />
            <p className="text-[13px] leading-relaxed text-ink-2">{openApproval.summary}</p>
            <p className="text-[12px] text-ink-3">
              Requested by {openApproval.requestedBy} · {f.ago(openApproval.requestedAt, now)}
            </p>
            <DecisionPanel
              idPrefix={`apr-${openApproval.id}`}
              noteLabel="Decision comment"
              hint="Required for every decision; stored with the approval record."
              decisions={[
                { id: "reject", label: "Reject", variant: "danger" },
                { id: "changes", label: "Request changes" },
                { id: "approve", label: "Approve", variant: "primary" },
              ]}
              onDecide={(d) => {
                setApprovals((xs) => xs.filter((x) => x.id !== openApproval.id));
                go("approvals");
                toast({ tone: d.id === "reject" ? "info" : "ok", title: `${openApproval.id} · ${d.label.toLowerCase()}`, body: "Recorded in this session only (demo)." });
              }}
            />
          </div>
        )}
      </Sheet>

      <NewTaskDialog
        open={creating}
        onClose={() => go("tasks")}
        onCreate={(t) => {
          setTasks((xs) => [t, ...xs]);
          go("tasks");
          toast({ tone: "ok", title: "Task created", body: `${t.title} · assigned to ${t.assignee} (demo).` });
        }}
      />
    </>
  );
}

/** "30 Sep" / "2 Oct" → Overdue · Due today · This week · Later, relative to the demo clock. */
function dueBucket(due: string) {
  const t = Date.parse(`${due} 2026 12:00 UTC`);
  if (Number.isNaN(t)) return due;
  const days = Math.floor((t - now.getTime()) / 86_400_000);
  return days < 0 ? "Overdue" : days === 0 ? "Due today" : days <= 7 ? "This week" : "Later";
}

/** "2h" → 2, "3d" → 72; used to sort exception age. */
function ageHours(age: string) {
  const n = parseFloat(age);
  return age.endsWith("d") ? n * 24 : age.endsWith("m") ? n / 60 : n;
}

function TitleCell({ title, sub }: { title: string; sub: string }) {
  return (
    <span className="block min-w-0">
      <span className="block truncate font-medium text-ink">{title}</span>
      <span className="block truncate text-[12px] text-ink-3">{sub}</span>
    </span>
  );
}

function NewTaskDialog({ open, onClose, onCreate }: { open: boolean; onClose: () => void; onCreate: (t: Task) => void }) {
  const [title, setTitle] = useState("");
  const [priority, setPriority] = useState<Severity>("medium");
  const [tried, setTried] = useState(false);
  const invalid = title.trim().length < 4;
  return (
    <ConfirmDialog
      open={open}
      title="New task"
      body="Tasks are visible to everyone in this workspace."
      confirmLabel="Create task"
      onCancel={() => (setTitle(""), setTried(false), onClose())}
      onConfirm={() => {
        if (invalid) return setTried(true);
        onCreate({ id: `TSK-${3200 + Math.floor(Math.random() * 99)}`, title: title.trim(), entity: { type: "Portfolio", id: "PORT", name: "Portfolio" }, type: "Follow-up", priority, due: "7 Oct", status: "To do", assignee: "You" });
        setTitle("");
        setTried(false);
      }}
    >
      <div className="mt-4 space-y-3">
        <Field id="task-title" label="Title" required error={tried && invalid ? "Give the task a title of at least 4 characters." : undefined}>
          <Input id="task-title" value={title} onChange={(e) => setTitle(e.target.value)} aria-invalid={tried && invalid} aria-describedby={tried && invalid ? "task-title-error" : undefined} data-autofocus />
        </Field>
        <Field id="task-priority" label="Priority">
          <Select id="task-priority" value={priority} onChange={(e) => setPriority(e.target.value as Severity)}>
            <option value="critical">Critical</option>
            <option value="high">High</option>
            <option value="medium">Medium</option>
            <option value="low">Low</option>
          </Select>
        </Field>
      </div>
    </ConfirmDialog>
  );
}

