"use client";

import { Fragment } from "react";
import { Building2 } from "lucide-react";
import { useTenants } from "@/lib/use-tenants";
import { PageBody, PageHeader } from "@/components/page/page-header";
import { EmptyState, ErrorState, FreshnessBadge, LoadingState, Skeleton } from "@/components/feedback";

/**
 * Renders a live, tenant-scoped panel only once the workspace is known.
 * Covers the three states every API-backed panel shares — memberships
 * loading, memberships failed, no membership — and keys the panel by tenant,
 * so switching workspaces remounts it and one tenant's inputs or results never
 * render under another.
 */
export function LiveTenantGate({ label, children }: { label: string; children: React.ReactNode }) {
  const { tenants, tenantId, loading, error, retry } = useTenants();

  if (loading) {
    return (
      <LoadingState label={label} className="space-y-3">
        <Skeleton className="h-9 w-2/3 rounded-md" />
        <Skeleton className="h-64 rounded-lg" />
      </LoadingState>
    );
  }
  if (error) {
    return (
      <div className="rounded-lg border border-line bg-surface">
        <ErrorState title="Workspaces unavailable" scope={`Your workspace memberships could not be loaded — ${error}.`} onRetry={retry} />
      </div>
    );
  }
  if (tenants.length === 0) {
    return (
      <div className="rounded-lg border border-line bg-surface">
        <EmptyState icon={<Building2 />} title="No workspace access yet" body="Your account is not a member of any workspace. Ask a workspace admin to add you, then reload this page." />
      </div>
    );
  }
  return <Fragment key={tenantId}>{children}</Fragment>;
}

/** A dashboard page whose body is one live OCTO API panel. */
export function LivePage({ eyebrow, title, description, children }: { eyebrow: string; title: string; description: string; children: React.ReactNode }) {
  return (
    <>
      <PageHeader variant="workflow" eyebrow={eyebrow} title={title} description={description} meta={<FreshnessBadge state="live" asOf="OCTO API" />} />
      <PageBody>
        <LiveTenantGate label={title}>{children}</LiveTenantGate>
      </PageBody>
    </>
  );
}
