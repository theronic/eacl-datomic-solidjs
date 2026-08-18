## 1. Point-Decision UI

- [x] 1.1 Add a per-permission decision component that posts the active subject, selected resource, schema permission, and cache mode to `/api/eacl/check-permission` and retains the last successful typed response.
- [x] 1.2 Render a schema-derived decision section immediately above the existing `lookup-subjects` permission-holder sections, with accessible allowed/denied status plus the existing elapsed-time and cache-provenance presentation.
- [x] 1.3 Key each decision only by semantic point-check inputs, abort superseded work, retain prior results during focused refresh, and isolate loading, error, and retry state per permission.
- [x] 1.4 Add responsive styles for the decision summary and its `view`/`admin` rows without changing the three-column desktop hierarchy or narrow-viewport flow.

## 2. Frontend Verification

- [x] 2.1 Add component fixtures and tests proving every selected-resource permission issues the correct check-permission body and renders independent decision, elapsed-time, and hit/miss/disabled metadata above holder lookups.
- [x] 2.2 Test no-selection behavior, schema-derived permission changes, cache/revision/subject/resource invalidation, lack of page-size/cursor/active-permission refetches, late-response suppression, retained refresh results, and per-row retry isolation.
- [x] 2.3 Extend the browser journey and accessibility checks to cover decision ordering, `view`/`admin` results, focused refresh/error feedback, desktop layout, and narrow viewport behavior against the real server.

## 3. Comparative Latency Benchmark

- [x] 3.1 Define stable check-permission and lookup-subjects benchmark request bodies alongside the existing lookup-resources request for the same seeded fixture.
- [x] 3.2 Refactor the HTTP sample helper to accept an operation descriptor, validate the response, parse `meta.elapsedMs` and `meta.cacheStatus`, and retain total boundary duration and payload bytes.
- [x] 3.3 Warm and sample `check-permission`, `lookup-resources`, and `lookup-subjects` independently, then report per-operation request/byte totals, provenance counts, and p50/p95/max server and HTTP-boundary latency distributions.
- [x] 3.4 Preserve the existing lookup-resources p95 target, report target status only where defined, and add deterministic tests for percentile aggregation and the nested benchmark result shape without adding wall-clock assertions to the regular suite.

## 4. Documentation and Final Verification

- [x] 4.1 Update the API/explorer and benchmark documentation to explain the new point-decision section, cache provenance, three-operation report, server-versus-boundary timing, and revised reference-run output.
- [x] 4.2 Run the frontend unit, type, lint, build, and focused Playwright checks; run the backend suite through nREPL with namespace reloads; and execute one opt-in benchmark to confirm all three operation summaries are populated.
