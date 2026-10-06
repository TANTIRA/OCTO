"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { AlertTriangle, ArrowLeft, ArrowRight, BarChart3, Database, GitBranch } from "lucide-react";
import { cn } from "@/lib/utils";
import { useFormat } from "@/lib/use-format";
import { LINEAGE_CHAINS, LINEAGE_NODES, MAPPINGS, type LineageChain, type LineageNode, type LineageNodeType } from "@/lib/demo";
import { Panel, PanelBody, PanelHead } from "@/components/page/panel";
import { Button, LinkButton, ringInset } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/badge";
import { Select } from "@/components/ui/controls";
import { Sheet } from "@/components/ui/overlay";
import { InlineAlert } from "@/components/feedback";
import { LineageFlow, type FlowLayer } from "./lineage-flow";

const TYPES: { type: LineageNodeType; layer: string; about: string }[] = [
  { type: "source", layer: "Sources", about: "The original incoming record from an administrator, custodian, bank or feed." },
  { type: "mapping", layer: "Mappings", about: "The rule that translates that record into OCTO's model." },
  { type: "ibor", layer: "IBOR", about: "The governed book of record, derived from the transaction ledger." },
  { type: "metric", layer: "Metrics", about: "The reported value, with a versioned definition." },
];
const TYPE_LABEL: Record<LineageNodeType, string> = { source: "Source", mapping: "Mapping", ibor: "IBOR object", metric: "Metric" };
const byId = new Map(LINEAGE_NODES.map((n) => [n.id, n]));
const isoLike = /^\d{4}-\d{2}-\d{2}T/;
/** Data & Sources destinations carry a `back` link so the user returns to the same lineage path (V3 LINEAGE-006). */
const withBack = (href: string, back: string) => (href.startsWith("/app/data") ? `${href}${href.includes("?") ? "&" : "?"}back=${encodeURIComponent(back)}` : href);

/**
 * Lineage explorer (V3 LINEAGE-001…006). Opens on a highlighted default path
 * with recent mapping changes, open lineage exceptions and popular metrics —
 * never an empty graph. Trace from any source, mapping or metric; select a
 * node to open a type-specific drawer; jump to the source, mapping or metric
 * and come back to the same path. Path and node live in the URL.
 */
export function LineageExplorer() {
  const f = useFormat();
  const router = useRouter();
  const path = usePathname();
  const params = useSearchParams();
  const chain = LINEAGE_CHAINS.find((c) => c.id === params.get("chain")) ?? LINEAGE_CHAINS[0];
  const node = byId.get(params.get("node") ?? "") ?? null;

  const go = (next: { chain?: string; node?: string | null }) => {
    const p = new URLSearchParams(params.toString());
    p.set("tab", "lineage");
    p.set("chain", next.chain ?? chain.id);
    if (next.node) p.set("node", next.node);
    else if (next.node === null) p.delete("node");
    router.replace(`${path}?${p}`, { scroll: false });
  };
  const chainFor = (id: string) => (chain.nodes.includes(id) ? chain : LINEAGE_CHAINS.find((c) => c.nodes.includes(id)) ?? chain);
  const select = (id: string) => go({ chain: chainFor(id).id, node: id });
  const backHref = `/app/data?tab=lineage&chain=${chain.id}`;

  const layers: FlowLayer[] = TYPES.map((t) => ({ layer: t.layer, about: t.about, nodes: LINEAGE_NODES.filter((n) => n.type === t.type).map((n) => ({ id: n.id, label: n.label, flag: n.flag })) }));
  const recent = [...MAPPINGS].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).filter((m) => byId.has(m.id)).slice(0, 3);
  const exceptions = LINEAGE_NODES.filter((n) => n.flag);
  const metrics = LINEAGE_NODES.filter((n) => n.type === "metric");
  const sourceNode = byId.get(chain.nodes[0])!;
  const metricNode = byId.get(chain.nodes[3])!;

  return (
    <div className="space-y-4">
      <Panel>
        <PanelHead
          title="Lineage"
          icon={<GitBranch />}
          description="Follow any reported number back to the record it came from. Pick where to start, then select a step for its detail."
          toolbar={
            <Select aria-label="Trace from" value="" onChange={(e) => e.target.value && select(e.target.value)} className="w-60 [&_select]:text-[12px]">
              <option value="">Trace from a source, mapping or metric…</option>
              {(["source", "mapping", "metric"] as const).map((t) => (
                <optgroup key={t} label={`${TYPE_LABEL[t]}s`}>
                  {LINEAGE_NODES.filter((n) => n.type === t).map((n) => (
                    <option key={n.id} value={n.id}>
                      {n.label}
                    </option>
                  ))}
                </optgroup>
              ))}
            </Select>
          }
        />
        <PanelBody className="space-y-4">
          {chain.note && <InlineAlert tone="warn" title={`${chain.name}: lineage exception`}>{chain.note}</InlineAlert>}
          <LineageFlow layers={layers} path={[...chain.nodes]} label={`${chain.name} lineage from source to metric`} onSelect={(_, n) => select(n.id)} />
          <div className="flex flex-wrap items-center gap-2 border-t border-line-subtle pt-3">
            <span className="text-[12px] text-ink-3">This path:</span>
            <Button size="sm" onClick={() => go({ node: sourceNode.id })}>
              <Database /> Inspect source
            </Button>
            <Button size="sm" onClick={() => go({ node: metricNode.id })}>
              <BarChart3 /> Inspect metric
            </Button>
            {metricNode.href && (
              <LinkButton size="sm" variant="ghost" href={metricNode.href}>
                Open {metricNode.label} <ArrowRight />
              </LinkButton>
            )}
          </div>
        </PanelBody>
      </Panel>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Shortcuts title="Recently changed mappings" items={recent.map((m) => ({ id: m.id, label: byId.get(m.id)!.label, meta: `Changed ${f.ago(m.updatedAt, new Date("2026-09-30T13:42:00Z"))} · ${m.status}`, tone: m.status === "Broken" ? "danger" : m.status === "Draft" ? "warn" : undefined }))} onPick={select} active={chain} />
        <Shortcuts title="Lineage exceptions" items={exceptions.map((n) => ({ id: n.id, label: n.label, meta: `${TYPE_LABEL[n.type]} · ${n.flag === "danger" ? "failing" : "delayed"}`, tone: n.flag }))} onPick={select} active={chain} />
        <Shortcuts title="Popular metrics" items={metrics.map((n) => ({ id: n.id, label: n.label, meta: n.facts[0][1] }))} onPick={select} active={chain} />
      </div>

      <Sheet
        open={!!node}
        onClose={() => go({ node: null })}
        eyebrow={node ? `${TYPE_LABEL[node.type]} · ${chain.name}` : ""}
        title={node?.label ?? ""}
        footer={
          node && (
            <div className="flex flex-wrap items-center justify-between gap-2">
              <Button variant="ghost" onClick={() => go({ node: null })}>
                <ArrowLeft /> Back to lineage
              </Button>
              <div className="flex flex-wrap gap-2">
                {sourceNode.href && (
                  <LinkButton href={withBack(sourceNode.href, backHref)} size="sm">
                    Open source
                  </LinkButton>
                )}
                {metricNode.href && (
                  <LinkButton href={metricNode.href} size="sm" variant="primary">
                    Open metric
                  </LinkButton>
                )}
              </div>
            </div>
          )
        }
      >
        {node && <NodeDetail node={node} chain={chain} onPick={select} backHref={backHref} />}
      </Sheet>
    </div>
  );
}

function NodeDetail({ node, chain, onPick, backHref }: { node: LineageNode; chain: LineageChain; onPick: (id: string) => void; backHref: string }) {
  const f = useFormat();
  const pos = chain.nodes.indexOf(node.id);
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap gap-2">
        <StatusBadge tone="info">Demo data</StatusBadge>
        {node.flag && (
          <StatusBadge tone={node.flag} icon={<AlertTriangle />}>
            {node.flag === "danger" ? "Failing" : "Needs attention"}
          </StatusBadge>
        )}
      </div>
      <dl className="divide-y divide-line-subtle rounded-md border border-line">
        {node.facts.map(([k, v]) => (
          <div key={k} className="grid grid-cols-[9rem_minmax(0,1fr)] gap-3 px-3 py-2 text-[13px]">
            <dt className="text-ink-3">{k}</dt>
            <dd className="break-words font-medium text-ink">{isoLike.test(v) ? `${f.dateTime(v)} UTC` : v}</dd>
          </div>
        ))}
      </dl>
      <section aria-label="Position in the chain">
        <h3 className="mb-2 text-[13px] font-semibold text-ink">Where this sits</h3>
        <ol className="space-y-1">
          {chain.nodes.map((id, i) => {
            const n = byId.get(id)!;
            return (
              <li key={id}>
                <button type="button" onClick={() => onPick(id)} aria-current={i === pos ? "step" : undefined} className={cn("flex w-full cursor-pointer items-center gap-2 rounded-sm px-2 py-1.5 text-left text-[13px]", i === pos ? "bg-accent-soft font-medium text-accent-ink" : "text-ink-2 hover:bg-hover", ringInset)}>
                  <span className="w-20 shrink-0 text-[11px] uppercase tracking-[0.05em] text-ink-4">{TYPE_LABEL[n.type]}</span>
                  <span className="min-w-0 flex-1 truncate">{n.label}</span>
                </button>
              </li>
            );
          })}
        </ol>
      </section>
      {node.href && node.type === "mapping" && (
        <Link href={withBack(node.href, backHref)} className="inline-flex items-center gap-1 text-[13px] font-medium text-accent-ink underline underline-offset-2">
          {node.hrefLabel} <ArrowRight aria-hidden className="size-3.5" />
        </Link>
      )}
    </div>
  );
}

function Shortcuts({ title, items, onPick, active }: { title: string; items: { id: string; label: string; meta: string; tone?: "warn" | "danger" }[]; onPick: (id: string) => void; active: LineageChain }) {
  return (
    <Panel>
      <PanelHead title={title} />
      <PanelBody flush>
        <ul className="divide-y divide-line-subtle">
          {items.map((it) => (
            <li key={it.id}>
              <button type="button" onClick={() => onPick(it.id)} className={cn("flex w-full cursor-pointer items-start gap-2 px-5 py-2.5 text-left hover:bg-hover", ringInset)}>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] font-medium text-ink">{it.label}</span>
                  <span className={cn("block truncate text-[12px]", it.tone === "danger" ? "text-danger" : it.tone === "warn" ? "text-warn" : "text-ink-3")}>{it.meta}</span>
                </span>
                {active.nodes.includes(it.id) && <StatusBadge tone="accent">On path</StatusBadge>}
              </button>
            </li>
          ))}
        </ul>
      </PanelBody>
    </Panel>
  );
}
