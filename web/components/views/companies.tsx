"use client";

import { useRouter } from "next/navigation";
import { useFormat } from "@/lib/use-format";
import { useCompanies } from "@/lib/data/queries";
import { DEMO_NOW, fundById, investmentsForCompany, type Company } from "@/lib/demo";
import { PageBody, PageHeader } from "@/components/page/page-header";
import { DataTable, type Column } from "@/components/data/data-table";
import { EntityCell, NumericCell, StatusCell } from "@/components/data/cells";
import { type Tone } from "@/components/ui/badge";
import { FreshnessBadge } from "@/components/feedback";
import { useBreadcrumb } from "@/components/shell/shell-context";

export const COMPANY_TONE: Record<Company["status"], Tone> = { Performing: "ok", Watch: "warn", "Covenant breach": "danger", Exited: "neutral" };
const now = new Date(DEMO_NOW);

/** Company directory (plan §38 `/app/companies`): search, filter, risk, performance, latest event. */
export function CompaniesView() {
  const f = useFormat();
  const router = useRouter();
  useBreadcrumb(null);
  const q = useCompanies();

  const cols: Column<Company>[] = [
    { id: "name", header: "Company", width: 260, hideable: false, value: (c) => c.name, cell: (c) => <EntityCell name={c.name} sub={`${c.subsector} · ${c.hq}`} href={`/app/companies/${c.id.toLowerCase()}`} /> },
    { id: "sector", header: "Sector", value: (c) => c.sector, facet: true, groupable: true },
    { id: "geo", header: "Geography", value: (c) => c.geography, facet: true, groupable: true },
    { id: "stage", header: "Stage", value: (c) => c.stage, facet: true, defaultHidden: true },
    { id: "status", header: "Status", value: (c) => c.status, facet: true, groupable: true, cell: (c) => <StatusCell tone={COMPANY_TONE[c.status]}>{c.status}</StatusCell> },
    { id: "owner", header: "Deal lead", value: (c) => c.owner, facet: true },
    { id: "funds", header: "Funds", value: (c) => investmentsForCompany(c.id).map((i) => fundById(i.fundId)!.short).join(", ") || "—" },
    { id: "revenue", header: "LTM revenue", value: (c) => c.revenue, align: "right", cell: (c) => (c.revenue ? <NumericCell value={c.revenue} /> : <span className="text-ink-4">—</span>) },
    { id: "growth", header: "Growth", value: (c) => c.revenueGrowth, align: "right", cell: (c) => (c.revenue ? <NumericCell value={c.revenueGrowth} kind="pct" muted /> : <span className="text-ink-4">—</span>) },
    { id: "margin", header: "EBITDA margin", value: (c) => (c.revenue ? (c.ebitda / c.revenue) * 100 : null), align: "right", cell: (c) => (c.revenue ? <NumericCell value={(c.ebitda / c.revenue) * 100} kind="pct" muted /> : <span className="text-ink-4">—</span>) },
    { id: "lev", header: "Net debt / EBITDA", value: (c) => (c.ebitda > 0 ? c.netDebt / c.ebitda : null), align: "right", cell: (c) => (c.ebitda > 0 ? <NumericCell value={c.netDebt / c.ebitda} kind="multiple" muted /> : <span className="text-ink-4">n/m</span>) },
    { id: "event", header: "Latest event", value: (c) => c.latestEvent.title, cell: (c) => (
      <span className="block max-w-72 truncate text-[12px]">
        <span className="text-ink-2">{c.latestEvent.title}</span> <span className="text-ink-4">· {f.ago(c.latestEvent.at, now)}</span>
      </span>
    ) },
  ];

  return (
    <>
      <PageHeader variant="list" eyebrow="Invest" title="Companies" description="Portfolio companies with status, risk and the latest event for each." meta={<FreshnessBadge state="demo" />} />
      <PageBody>
        <DataTable
          id="companies"
          label="Companies"
          data={q.data ?? []}
          status={q.isLoading ? "loading" : "ready"}
          columns={cols}
          rowId={(c) => c.id}
          demo
          onRowOpen={(c) => router.push(`/app/companies/${c.id.toLowerCase()}`)}
          exportName="octo-companies"
          searchPlaceholder="Search companies, sectors, leads…"
          views={[
            { id: "active", name: "Active portfolio", state: { facets: { status: ["Performing", "Watch", "Covenant breach"] }, sort: [{ id: "revenue", desc: true }] } },
            { id: "risk", name: "Watch list", state: { facets: { status: ["Watch", "Covenant breach"] } } },
            { id: "sector", name: "By sector", state: { groupBy: "sector" } },
          ]}
          empty={{ title: "No companies", body: "Companies are created from invested deals or imported from the CRM." }}
        />
      </PageBody>
    </>
  );
}
