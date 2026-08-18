## Why

The explorer exposes latency for resource and subject lookups, but it does not show the point authorization decision for the active subject and selected resource. As a result, multi-millisecond lookup timings do not make the expected faster `eacl/can?`/`eacl/check-permission` path clear or directly comparable.

## What Changes

- Add a permission-decision section above the existing right-sidebar permission-holder lookups.
- For the active subject and selected resource, run the existing `eacl/check-permission` path independently for every permission defined on that resource type (currently `:view` and `:admin`) and show each allowed/denied result with its own elapsed time and cache provenance.
- Reuse the existing `/api/eacl/check-permission` contract so the point-check measurement retains accurate hit, miss, or disabled status.
- Keep point checks reactive to subject, resource, schema/revision, and cache-mode changes, with focused loading, stale-response suppression, error, and retry behavior that does not disturb `lookup-subjects` results.
- Extend performance measurement and documentation so `check-permission`, `lookup-resources`, and `lookup-subjects` server and HTTP-boundary latency can be reported separately under the same repeatable fixture and sampling conventions.

## Capabilities

### New Capabilities

- `permission-decision-observability`: Covers per-permission `eacl/check-permission` decisions in the selected-resource sidebar, elapsed time and cache provenance, and comparative latency measurement across point, resource-lookup, and subject-lookup operations.

### Modified Capabilities

None. This project has no main specs yet; the new behavioral contract is scoped to the capability above.

## Impact

- SolidJS detail-sidebar components, request types, styling, and focused component/browser tests.
- Existing Clojure point-check response metadata and backend test fixtures; no new API route is required.
- The opt-in development benchmark and README benchmark/API documentation.
- No new external dependencies and no API contract changes to the existing lookup or check-permission endpoints.
