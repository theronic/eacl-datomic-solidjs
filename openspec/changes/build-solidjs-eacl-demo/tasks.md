## 1. Repository and Build Scaffolding

- [x] 1.1 Create the root, `client/`, and `server/` project structure with ignore rules, shared development commands, and a README skeleton.
- [x] 1.2 Scaffold the TypeScript SolidJS/Vite client and pin Solid, Vite, test, browser-test, and lazy graph dependencies.
- [x] 1.3 Scaffold the Clojure server with pinned Ring/router/JSON, Datomic Pro, EACL Datomic, logging, test, and nREPL dependencies and aliases.
- [x] 1.4 Configure Vite to proxy `/api` during development and configure the server to serve a production client build from the same origin.
- [x] 1.5 Add secret-free example environment configuration for trusted-LAN bind address, the H2-backed Datomic `:dev` URI, token keys, request limits, and development defaults.

## 2. Datomic, EACL, and Server Lifecycle

- [x] 2.1 Implement configuration parsing and fail-fast validation, including all-interface trusted-LAN development binding, a loopback override, a durable Datomic `:dev` default, and persistent token-key requirements.
- [x] 2.2 Implement explicit start, stop, and restart lifecycle functions for the Datomic connection, EACL client, cache, executor, and HTTP listener.
- [x] 2.3 Install EACL Datomic storage attributes idempotently and construct the client with managed coherence authority.
- [x] 2.4 Port the reference default Spice schema, schema presets, quick subjects, and small idempotent demo foundation without coupling to either source repository.
- [x] 2.5 Port benchmark-shaped append-only seed planning and EACL-managed mutation execution with bounded input, progress state, and compare-and-set overlap protection.
- [x] 2.6 Implement the opaque composite revision from Datomic basis and cache generation and expose safe helpers for query and mutation responses.

## 3. HTTP Boundary and Common Contracts

- [x] 3.1 Implement JSON success/error envelopes, EACL elapsed/cache metadata, JSON-safe domain conversion, request ids, and sanitized exception mapping.
- [x] 3.2 Implement shared validators for media type, body size, strings, schema-known names, object shapes, cache mode, page sizes, seed counts, and opaque cursors.
- [x] 3.3 Add `GET /api/health` and `GET /api/bootstrap` with readiness, revision, seed status, totals, schema metadata, quick subjects, presets, and page-size options.
- [x] 3.4 Add bounded, deterministically ordered known-subject identifier paging while keeping EACL object responses as passthrough type/id pairs without display-name enrichment.
- [x] 3.5 Add HTTP middleware for JSON negotiation, safe logging, request timeouts, same-origin static assets, cache headers, and API not-found/method errors.

## 4. EACL Query Endpoints

- [x] 4.1 Implement and test `POST /api/eacl/lookup-resources` with first/after pagination, opaque `pageInfo`, timing, and cache provenance.
- [x] 4.2 Implement and test `POST /api/eacl/count-resources` as an exact non-page query independent from lookup cursors.
- [x] 4.3 Implement and test `POST /api/eacl/lookup-subjects` with bounded results and pagination metadata.
- [x] 4.4 Implement and test `POST /api/eacl/read-relationships` with bounded traversal inputs and opaque continuation.
- [x] 4.5 Implement and test `POST /api/eacl/check-permission` with authoritative allowed/denied output.
- [x] 4.6 Map malformed or mismatched authenticated cursor failures to a stable `409 invalid-cursor` response and verify page-one recovery inputs remain valid.

## 5. Schema, Cache, and Seed Mutation Endpoints

- [x] 5.1 Implement `GET /api/schema` with committed Spice source, graph nodes/links, counts, presets, and revision.
- [x] 5.2 Implement `PUT /api/schema` through EACL's schema writer with `422` diagnostics, prior-schema preservation on failure, and post-commit revision metadata.
- [x] 5.3 Implement `GET /api/cache` as a side-effect-free JSON-safe provider snapshot with capture metadata.
- [x] 5.4 Implement serialized `POST /api/cache/evict`, clear the EACL cache, advance cache generation, and return the new revision.
- [x] 5.5 Implement reserved asynchronous `POST /api/seed` plus progress/status reads, reject overlapping jobs, and expose each committed data revision.

## 6. Backend Verification

- [x] 6.1 Add unit tests for configuration, validators, JSON conversion, envelopes, error sanitization, revision construction, and lifecycle cleanup.
- [x] 6.2 Add Ring handler tests covering every route, method, content type, invalid boundary, status code, and non-leaking error shape.
- [x] 6.3 Add Datomic-memory integration fixtures and tests for real resource lookup/count, subject lookup, relationship traversal, permission checks, and cursor continuations.
- [x] 6.4 Add integration tests proving failed schema writes are non-destructive, successful writes advance revision, cache provenance/eviction work, and seed overlap is rejected.
- [x] 6.5 Add a development namespace and document/run the backend suite through a persistent nREPL with namespace reloads.

## 7. SolidJS Application Foundation

- [x] 7.1 Define TypeScript request, response, error, revision, EACL object, pagination, schema, cache, seed, and bootstrap contracts matching the server.
- [x] 7.2 Implement the centralized `/api/*` fetch adapter with JSON validation, `AbortController`, request identity, stable errors, and test injection points.
- [x] 7.3 Implement top-level signals for subject, permission, selected resource, page size, cache mode, theme, mutation revision, and seed status.
- [x] 7.4 Implement guarded local-preference restore/persistence and normalize stored subject, permission, page-size, expansion, cache, and theme values after bootstrap.
- [x] 7.5 Copy and attribute the `eacl-explorer` stylesheet, merge Electric's page-size styles, and add scoped Solid-specific loading, refresh, dark-theme, disclosure, and accessibility rules.
- [x] 7.6 Build reusable Solid components for disclosure controls, type badges, cache/timing provenance, pagination, empty/error/loading states, and retry actions.

## 8. Reactive Explorer Panels

- [x] 8.1 Build the header with status/totals, source links, coherent light/dark document themes, validated seed controls, and the page-size dropdown values `10` through `1000` with default `20`.
- [x] 8.2 Build the subjects panel with quick subjects, bounded known-subject paging that survives active-subject selection, schema-derived permission chips, active state, and focused async feedback.
- [x] 8.3 Build resource-type group components with narrowly keyed page and a single independent count resource, stable keyed rows, `range (page timing/status) of total (count timing/status)`, collapsed-stat hiding, and first/previous/next controls.
- [x] 8.4 Reset the correct cursor stacks before subject, permission, page-size, cache-mode, data-revision, or schema-revision refetches and recover invalid cursors once from page one.
- [x] 8.5 Build lazy nested relationship sections with per-parent/type cursors, required HTTP permission checks, stable keys, expansion persistence, and ancestry cycle guards.
- [x] 8.6 Build selected-resource detail with locally derived labels and per-permission subject lookup groups that can change the active subject.
- [x] 8.7 Verify superseded HTTP responses cannot overwrite current resources, focused refetches preserve unrelated panels and prior successful data, and panels remain queryable while seed revisions arrive.

## 9. Schema and Cache Segments

- [x] 9.1 Build the controlled Spice schema draft, preset tabs, counts, dirty/writing/error states, and disabled-state rules for **Write Schema**.
- [x] 9.2 Connect schema writes so success adopts the committed source/revision, normalizes removed permissions, clears affected cursors, and refetches visible schema-dependent resources while failure retains the draft and prior results.
- [x] 9.3 Build a lazy Solid-owned D3 schema graph with resize behavior and complete effect, simulation, listener, and animation cleanup.
- [x] 9.4 Build cache enablement and **Evict Cache** controls with mutation-revision invalidation that does not touch the displayed cache snapshot.
- [x] 9.5 Build **Refresh cache** as the only cache-snapshot fetch/write path, retain the prior snapshot on refresh failure, label capture time/mode, and render deterministic two-space-indented JSON in `<pre><code>`.

## 10. Frontend and Browser Verification

- [x] 10.1 Add Vitest and Solid Testing Library coverage for API cancellation, resource source keys, cursor resets, invalid-cursor recovery, late-response suppression, and local preference fallback.
- [x] 10.2 Add component tests for subject/permission selection, page-size behavior, independent count stability, nested cycles, detail selection, and focused errors.
- [x] 10.3 Add schema tests for dirty/write states, successful invalidation, invalid-draft retention, permission normalization, and lazy graph cleanup.
- [x] 10.4 Add cache tests proving open/query/toggle/evict/schema/seed actions never fetch or replace the snapshot and only **Refresh cache** does so.
- [x] 10.5 Add Playwright flows against the real server for the three-panel journey, cursor pagination, page size, valid/invalid schema writes, reactive background seeding, cache eviction/manual refresh, and retry behavior.
- [x] 10.6 Add keyboard, accessible-name, live-status, dark-theme, desktop, and narrow-viewport checks with focused visual snapshots.

## 11. Performance, Packaging, and Handoff

- [x] 11.1 Add per-operation structured metrics and a benchmark fixture/runner that records request count, payload bytes, p50, and p95 for 10,000 seeded servers.
- [x] 11.2 Verify server-side bounded pagination, lazy nested loading, stable Solid list identities, compressed assets, and lazy graph code; document the warmed default-page p95 target of 250 ms and any variance.
- [x] 11.3 Build the production client into server resources and verify same-origin routing, immutable asset caching, SPA fallback, API precedence, and `/api/health` from a clean start.
- [x] 11.4 Complete README transactor/quickstart, REPL workflow, architecture, `/api/*` request/response/error examples, environment options, security limitations, troubleshooting, and benchmark instructions.
- [x] 11.5 Run backend tests via nREPL, frontend unit/type/lint checks, production builds, Playwright flows, API integration tests, and the benchmark; record final verification results and resolve all failures.

## 12. Seed and Cache Invalidation Corrections

- [x] 12.1 Reconcile seed progress only from actual bootstrap responses, keep polling across intermediate mutation revisions, and verify terminal progress/totals converge without replacing the explorer.
- [x] 12.2 Prevent no-op cursor resets from issuing duplicate page lookups after cache eviction, verify one lookup and one count refetch, and visually separate locally derived descriptions from passthrough ids.
- [x] 12.3 Keep recursive nested authorization bounded by bypassing complete-answer caching for one-off child permission checks, verify schema writes are no longer starved behind a full forward denotation, and rename the default preset to **Non-recursive**.
- [x] 12.4 Split page and exact-count timing into independent Solid DOM fragments so page navigation cannot flash or replace stable count provenance.
