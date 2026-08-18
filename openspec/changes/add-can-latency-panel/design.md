## Context

See `proposal.md` for motivation and `specs/permission-decision-observability/spec.md` for the behavioral contract. The right column is currently a single `DetailPanel`: once a resource is selected it renders a local header followed by one independently paged `PermissionSubjects` branch per schema permission. Those branches call `/api/eacl/lookup-subjects` and render the server's elapsed/cache metadata with `MetaTiming`.

The server already exposes `/api/eacl/check-permission`, invokes `eacl/check-permission`, and returns elapsed time plus accurate cache provenance. This is the detailed form of the same point-authorization path used by `can?` and is the preferred measurement source. The current UI never calls it for the selected-resource summary, and the opt-in benchmark samples only `/api/eacl/lookup-resources`, so the expected faster point-check path is not visible beside the multi-millisecond lookups.

## Goals / Non-Goals

**Goals:**

- Make the existing `eacl/check-permission` point-check path visible and measurable without losing cache provenance.
- Keep point decisions independently reactive and visually ordered above the existing permission-holder branches.
- Reuse the existing validation, cancellation, error-envelope, timing, request-suppression, and accessibility conventions.
- Produce comparable per-operation benchmark distributions without folding unlike queries into one percentile.

**Non-Goals:**

- Replacing or changing `/api/eacl/check-permission` or any lookup endpoint contract.
- Moving authorization logic into the browser or deriving `can?` from `lookup-subjects` membership.
- Hard-coding `view` and `admin`; they are examples from the current account schema, while the UI remains schema-driven.
- Adding a production telemetry backend or hardware-sensitive latency assertions to the functional suite.

## Decisions

### Reuse `/api/eacl/check-permission`

Each decision row will post the active subject, its permission, the selected resource, and cache mode to the existing point-check route. The response already has the required `{:allowed boolean}` data and standard `elapsedMs`/`cacheStatus` metadata, while server metrics already identify `:check-permission` separately from both lookup operations.

No additional endpoint or response adaptation is needed. Backend work is limited to retaining regression coverage and adding benchmark request fixtures so the new UI and performance report exercise the existing contract exactly as deployed.

Alternative considered: add a Boolean-only `/api/eacl/can` route. Rejected because it would discard cache provenance and duplicate an existing point-decision contract; `check-permission` exposes the result and metadata needed to understand whether a multi-millisecond sample was a hit, miss, or cache-disabled execution.

### Model each visible permission as an independent Solid resource

Add a `PermissionDecision` row component and a containing decision section inside `DetailPanel`, immediately after the selected-resource header and before the existing `PermissionSubjects` loop. The containing section iterates the same schema-derived permission array as the lookup branches, so `view`/`admin` appear when defined and schema edits are reflected without a second permission source.

Each row's request source will be limited to active subject type/id, selected resource type/id, its permission, cache mode, and the current authorization mutation/schema revision. Page size, lookup cursor, and globally selected resource-list permission are deliberately excluded. A row owns its `LatestRequest`, last successful envelope, focused refresh indicator, error, retry, and cleanup so one permission or `lookup-subjects` failure cannot replace another branch.

Alternative considered: issue one client request containing all permissions. Rejected because it would introduce a batch contract, obscure the one-point-check timing boundary, and make per-permission retry and failure isolation harder. The current schema exposes only a small permission set; the existing server admission limit remains the safety bound.

### Show decisions, elapsed time, and cache provenance together

Successful rows will render an accessible allowed/denied status next to the existing `MetaTiming`, which presents both `meta.elapsedMs` and `meta.cacheStatus`. During refetch, the prior successful decision remains visible and is marked busy until the current tuple succeeds; a late superseded response is discarded by `LatestRequest`.

Alternative considered: time the browser fetch with `performance.now()`. Rejected because it would include transport and rendering costs while existing lookup timings are server-reported, preventing an apples-to-apples operation comparison.

### Benchmark all three operations through parameterized HTTP samples

Refactor the development benchmark's single hard-coded request into operation descriptors for `/api/eacl/check-permission`, `/api/eacl/lookup-resources`, and `/api/eacl/lookup-subjects`, each with a stable valid body drawn from the same seeded fixture. Warm each descriptor independently, collect the configured number of samples, parse the standard response metadata, and return per-operation request count, total/average response bytes, cache-status counts, and p50/p95/max distributions for both `meta.elapsedMs` and the surrounding Ring HTTP-boundary duration.

The existing 10,000-server fixture and warmed default-page target remain intact for `lookup-resources`. The report will expose `check-permission` and `lookup-subjects` distributions without inventing new pass/fail thresholds; the separate server and HTTP numbers will show whether unexpected multi-millisecond cost is in EACL execution or boundary overhead. Thresholds can be added later from measured reference data. README examples and the recorded reference-run shape will be updated to match the nested result.

Alternative considered: call EACL functions directly in the benchmark. Rejected because the existing numbers measure the Ring boundary and the requested comparison should preserve that convention.

## Risks / Trade-offs

- [Selecting a resource adds one request per schema permission] → Key requests only by semantic inputs, cancel superseded calls, and rely on the existing bounded authorization admission control.
- [A schema with many permissions can momentarily exceed server concurrency] → Keep failures isolated and retryable; the current demo's `view`/`admin` set stays within the configured limit, and batching can be proposed separately if real schemas require it.
- [Warmed samples can hide miss behavior] → Preserve cache status for every sample and summarize provenance counts next to the latency distribution.
- [Benchmark operations have different result shapes and graph work] → Report separate distributions and payload statistics rather than aggregating them into a single latency number.
- [Retaining prior decisions during refresh can look current] → Mark the row as refreshing and never accept a response whose request identity no longer matches the current tuple.

## Migration Plan

1. Add the decision section using the existing point-check endpoint while leaving permission-holder rendering unchanged.
2. Extend component and browser coverage for the new requests and metadata; retain backend contract regression coverage.
3. Update the benchmark and documentation with the separate point-check and lookup distributions.
4. Roll back by removing the new section and benchmark descriptors; existing API consumers require no migration.
