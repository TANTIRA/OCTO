# Availability burn-rate rules — #303

This is a tested rule pack for a Prometheus-compatible evaluator. It is **not
loaded by the OTEL collector** and does not enable production notifications.
The collector (#306) already receives telemetry; its default exporter only logs.
Backend provisioning, routing and production activation belong to Platform/SRE.

## Metric contract — verify before loading

The evaluator must expose `http_server_requests_seconds_count` as a cumulative
counter with `job="octo-api"`, nonempty `deployment`, `instance`, `uri`, and
HTTP `status` labels. `deployment` must uniquely identify the project/environment
(for example `octo-staging` or `octo-prod`), be attached by trusted ingestion
configuration, and be present on every instance. It is a **required integration
label, not one the current collector already adds**. Missing deployment labels
are intentionally not aggregated. Within each deployment this job must refer
only to the OCTO API.

OTLP conversion can change names/labels: the Prometheus receiver maps its job
onto OTEL `service.name`; confirm the backend maps it back to the selector above.
Do not assume the existing generic OTLP overlay guarantees this contract. Check
actual backend samples and label coverage before enabling the rules. Do not
attach JWTs, tenant IDs, user IDs or request IDs as metric labels.

## Rules

The availability objective is 99.9%, so its error budget is 0.001. The numerator
is eligible 5xx requests; the denominator is all eligible requests, including
ordinary 4xx. Both exclude `/actuator` and its descendants, and 401/403, matching
`docs/reliability.md`. Rates are computed per instance before aggregation.

| Alert | Long / short windows | Burn | Severity |
| --- | --- | --- | --- |
| `OctoAvailabilityBurnFast` | 1h / 5m | >14.4x | page |
| `OctoAvailabilityBurnSlow` | 6h / 30m | >6x | page |
| `OctoAvailabilityBurnTicket` | 3d / 6h | >1x | ticket |

Both windows must exceed the threshold. No additional `for` duration delays the
budget-based detection. The short-window choices follow the Google SRE
multi-window examples; SRE should review them against the OCTO traffic profile.
Overlapping alerts can fire together; notification grouping/inhibition belongs
to the selected alert router.

With observed traffic and no 5xx series the error ratio is zero. A zero traffic
rate is undefined (NaN), not a healthy observation; no telemetry produces no
ratio. Neither condition fires these burn alerts. Telemetry loss and a complete
service outage need separate health/scrape monitoring. Partial loss of replicas'
metrics can bias the ratio and also needs independent telemetry monitoring.

Rate windows use available samples, not proof of a full window of continuous
history. Before relying on the 3d rule, provide at least three days of retained
metrics and validate ingestion continuity. Low traffic can make a single failure
significant; there is deliberately no arbitrary request-volume floor hiding it.

Planned-maintenance exclusion is not instrumented in this pack. An alert silence
suppresses notifications but does **not** remove requests from SLI calculations.
SRE must establish that exclusion before describing this as the full policy SLI.

## Validate

From the repo root, using `promtool` (validated with version 3.5.0):

```bash
promtool check rules deploy/alerts/availability.rules.yml
promtool test rules deploy/alerts/availability.test.yml
```

Tests cover sustained burns at each tier, exact thresholds, both-window gating,
recovery, missing error series, auth/actuator exclusion, ordinary 4xx, zero and
absent traffic, deployment isolation, absent deployment labels, and resets.

## Activation checklist — SRE

1. Choose/provision a backend that retains metrics and evaluates PromQL rules.
   Route metrics from the collector to it; the current `debug` exporter is not a
   storage backend. Confirm the metric contract above using real samples.
2. Load `availability.rules.yml` through that backend's rule loader (Prometheus
   uses `rule_files`). Keep recording and alert rules together and in order.
3. Confirm all eight rules load and evaluate without errors, ratios match the
   underlying counters, and deployment isolation holds. Cover absent telemetry
   through separate scrape/health alerts.
4. Configure page/ticket destinations, grouping and inhibition with the operator.
   Verify a synthetic staging alert reaches the intended receiver and resolves;
   retain sanitized evidence. Do not induce financial writes or a production
   outage to test routing.
5. Record the deployment, backend/evaluator version, label mapping, activation
   time, rule results and notification evidence in the #303 review. Unit tests
   alone are not evidence of live alert delivery.

Rollback: unload this rule file from the evaluator and revert the associated
routing change. This pack changes no application code, database or collector.

## Remaining #303 scope

Durability's 99.95% objective refers to committed writes on ledger/workflow/audit
endpoints. HTTP 2xx/5xx counts cannot prove a commit, especially when a response
fails after the transaction commits. API Engineering and SRE must agree on
transaction-outcome instrumentation and denominator semantics before adding its
rules. Do not relabel availability as durability. Backend provisioning, live
notification delivery and the durability rules remain outstanding: this pack
should use `Refs #303`, not `Closes #303`.

References:
- https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/
- https://prometheus.io/docs/prometheus/latest/querying/functions/
- https://prometheus.io/docs/guides/opentelemetry/
- https://sre.google/workbook/alerting-on-slos/
