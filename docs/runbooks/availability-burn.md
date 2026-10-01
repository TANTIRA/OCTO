# OCTO availability budget burn

Owner: Platform/SRE (Aldroun); API Engineering assists with request failures.
Triggered by the availability rules in `deploy/alerts/availability.rules.yml`.

## Confirm and locate the failure

1. Read the alert's `deployment`, severity and evaluation time. In that deployment's
   backend compare the paired error ratios to 0.0144 (fast), 0.006 (slow), or
   0.001 (ticket). Confirm counters are current, labels match and the denominator
   excludes auth rejections and actuator traffic. If ingestion is missing or
   stale, investigate the collector/backend; missing telemetry is not recovery.
2. Check that deployment's liveness/readiness probes. An unhealthy database in
   readiness points to the database path; also check connection exhaustion,
   Redis/vendor failures and recent configuration/deploy changes.
3. Break down eligible 5xx by endpoint, status and API instance. Correlate logs
   using existing correlation IDs; do not paste credentials, payloads or client
   personal data into incident evidence. Check whether one replica, one route,
   or the entire deployment is failing.

## Respond

- **Page:** the on-call operator investigates now. If a recent deployment caused
  the failure, use the approved code rollback procedure in `deploy/README.md`,
  after checking schema compatibility. Applied Flyway migrations are forward-only;
  do not revert them or overwrite financial data as an alert response.
- **Ticket:** capture the affected routes, window and error trend for planned
  remediation. Escalate if it also meets a page condition or is an active outage.
- Follow the error-budget release policy in `docs/reliability.md`. Silencing a
  duplicate alert or planned-maintenance notification does not erase SLI errors.

## Verify recovery and retain evidence

Confirm readiness, representative tenant-scoped reads and the short-window error
rate recover while telemetry stays fresh. The paired alert should resolve once
either window falls below its threshold; long-window errors may persist after
recovery. Verify the notification receiver gets resolution according to its
routing configuration. Record deployment, timeline, cause, intervention and
sanitized evidence; leave a follow-up for any instrumentation or routing gap.
