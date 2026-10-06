"use client";

import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { useFormat } from "@/lib/use-format";
import type { Provenance } from "@/lib/demo";
import { Sheet } from "@/components/ui/overlay";
import { StatusBadge } from "@/components/ui/badge";
import { AiCitation, AiConfidence, VerificationBadge } from "@/components/ai/ai-badge";

/**
 * Universal lineage drawer (plan §24). Clicking any metric shows formula,
 * inputs, as-of, source system and document, transformation, and version —
 * plus confidence, citations and reviewer when the value is AI-derived.
 */
export function LineageDrawer({ open, onClose, title, value, provenance, demo = true }: { open: boolean; onClose: () => void; title: string; value: string; provenance: Provenance | null; demo?: boolean }) {
  const f = useFormat();
  if (!provenance) return null;
  const p = provenance;
  return (
    <Sheet open={open} onClose={onClose} eyebrow="Lineage" title={`${title} · ${value}`}>
      <div className="flex flex-wrap gap-2">
        {demo && <StatusBadge tone="info">Demo data — illustrative calculation</StatusBadge>}
        {p.ai ? <VerificationBadge state="ai-suggested" /> : <VerificationBadge state="source-derived" />}
      </div>

      <ol className="mt-5 space-y-0" aria-label="Calculation path">
        <Step n={1} title="Formula">
          <code className="block rounded-md border border-line bg-subtle px-3 py-2 font-data text-[12px] text-ink">{p.formula}</code>
        </Step>
        <Step n={2} title="Inputs">
          <ul className="divide-y divide-line rounded-md border border-line">
            {p.inputs.map((i) => (
              <li key={i.label} className="flex items-center justify-between gap-3 px-3 py-2 text-[13px]">
                {i.href ? (
                  <Link href={i.href} className="inline-flex items-center gap-1 text-ink-2 hover:text-accent" onClick={onClose}>
                    {i.label} <ArrowRight aria-hidden className="size-3" />
                  </Link>
                ) : (
                  <span className="text-ink-2">{i.label}</span>
                )}
                <span className="tabular-nums font-medium text-ink">{i.value}</span>
              </li>
            ))}
          </ul>
        </Step>
        <Step n={3} title="Transformation">
          <p className="text-[13px] text-ink-2">{p.transformation}</p>
        </Step>
        <Step n={4} title="Source" last>
          <dl className="grid grid-cols-2 gap-3 text-[13px]">
            <div>
              <dt className="text-[12px] text-ink-3">System</dt>
              <dd className="mt-0.5 text-ink-2">{p.sourceSystem}</dd>
            </div>
            <div>
              <dt className="text-[12px] text-ink-3">Document</dt>
              <dd className="mt-0.5 text-ink-2">{p.sourceDocument ?? "—"}</dd>
            </div>
            <div>
              <dt className="text-[12px] text-ink-3">As of</dt>
              <dd className="mt-0.5 text-ink-2">{f.dateTime(p.asOf)} UTC</dd>
            </div>
            <div>
              <dt className="text-[12px] text-ink-3">Definition</dt>
              <dd className="mt-0.5 text-ink-2">{p.version}</dd>
            </div>
          </dl>
        </Step>
      </ol>

      {p.ai && (
        <div className="mt-6 rounded-lg border border-ai/25 bg-ai/5 p-3">
          <div className="flex items-center justify-between gap-2">
            <p className="text-[12px] font-medium text-ink">AI-derived value</p>
            <AiConfidence level={p.ai.confidence} />
          </div>
          <ol className="mt-2 space-y-1">
            {p.ai.citations.map((c, i) => (
              <AiCitation key={c} n={i + 1} label={c} />
            ))}
          </ol>
          <p className="mt-2 text-[12px] text-ink-3">
            {p.ai.verification}
            {p.ai.reviewer && ` · reviewed by ${p.ai.reviewer}`}
          </p>
        </div>
      )}
    </Sheet>
  );
}

function Step({ n, title, last, children }: { n: number; title: string; last?: boolean; children: React.ReactNode }) {
  return (
    <li className="relative pb-5 pl-8">
      {!last && <span aria-hidden className="absolute bottom-0 left-[9px] top-6 w-px bg-line" />}
      <span className="absolute left-0 top-0 flex size-5 items-center justify-center rounded-full border border-line bg-surface text-[10px] font-semibold text-ink-3">{n}</span>
      <p className="text-[12px] font-medium text-ink-3">{title}</p>
      <div className="mt-1.5">{children}</div>
    </li>
  );
}
