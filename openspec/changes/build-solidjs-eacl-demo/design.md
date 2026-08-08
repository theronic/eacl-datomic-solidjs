## Context

`eacl-explorer` is a browser-only Rum/DataScript application, so its EACL calls and database live in the browser. `electric-eacl` supplies the Datomic Pro reference and a reactive server-side implementation, but its UI values are transported by Electric rather than conventional HTTP. `eacl-solidjs` is empty and can establish a clean third reference: EACL and Datomic Pro remain server-only, while a SolidJS client obtains every result through `/api/*` JSON requests.

The implementation must preserve the explorer's information architecture and CSS language, add Electric's page-size control, support live schema editing, and keep cache inspection explicitly manual. It must also remain responsive with benchmark-shaped data, opaque authenticated cursors, overlapping requests, and schema changes that can invalidate every authorization query.

## Goals / Non-Goals

**Goals:**

- Deliver a standalone development and production application with a SolidJS/Vite client and a Clojure/Ring backend.
- Keep Datomic Pro, the EACL Datomic client, cache storage, token keys, schema parsing, and authorization execution exclusively on the server.
- Provide small, validated, documented `/api/*` contracts for every explorer read and mutation.
- Model remote data with Solid signals, memos, resources, keyed lists, and component-local state so only affected UI branches rerun or refetch.
- Preserve the existing explorer's panels, resource tree, schema graph/editor, cache provenance, styling, responsive behavior, theme, and demo seeding workflow.
- Make performance visible and repeatable through timings, bounded pagination, integration tests, and a local benchmark profile.

**Non-Goals:**

- Replacing or modifying `eacl-explorer`, `electric-eacl`, EACL, or their public APIs.
- Shipping a generic EACL JavaScript SDK or allowing the browser to execute EACL locally.
- Providing production identity authentication, tenant isolation, or unrestricted internet-safe schema/seed administration; this remains a local reference application.
- Streaming arbitrary out-of-process Datomic changes to the browser. Reactivity covers UI inputs and mutations performed through this application's API; a reload discovers unrelated external writes.
- Reproducing Electric's runtime or sharing source files across the reference repositories.

## Decisions

### 1. Use two explicit runtime boundaries in one repository

The repository will contain a `client/` SolidJS/Vite application and a `server/` Clojure application, plus root development commands and documentation. Vite will proxy `/api` to Ring during development. A production client build will be copied into the server's static resources so API and assets can be served from one origin.

This layout makes the HTTP boundary impossible to bypass and allows each side to use its normal test and build tools. A ClojureScript Solid wrapper was rejected because it would obscure the intended TypeScript/SolidJS reference. A single Node service was rejected because the supported Datomic Pro peer and EACL Datomic adapter belong on the JVM.

### 2. Give the backend explicit lifecycle ownership

The server system will own the HTTP server, Datomic connection, EACL client, cache store, demo seed state, and a small cache-generation counter. The default development URI is `datomic:dev://localhost:4334/eacl-solidjs`, served by Datomic Pro's embedded H2 transactor under `~/datomic/1.0.7705`. Startup idempotently installs EACL storage attributes and the demo foundation before constructing the EACL client with managed coherence authority, following `electric-eacl`. Unit and integration fixtures continue to use isolated `datomic:mem` databases.

Persistent deployments must provide token key material through environment configuration. The development server binds to all interfaces by default so the demo is reachable through the host's `.local` name on a trusted Wi-Fi network; `EACL_SOLIDJS_HOST=127.0.0.1` restores machine-local access. The server validates configuration before listening, closes owned resources on stop, and exposes `/api/health`. Explicit lifecycle functions are preferred over adding a component framework to keep the demo small and REPL-friendly.

### 3. Define operation-oriented JSON endpoints

The API will expose these contracts:

- `GET /api/bootstrap` for demo status, current revision, schema-derived resource types and permissions, quick subjects, totals, page-size options, and schema presets.
- `GET /api/subjects` for offset-bounded known-user identifier pages. EACL objects cross the wire as passthrough `{type, id}` values; readable labels are derived by the client and are not added to query responses.
- `POST /api/eacl/lookup-resources`, `/api/eacl/count-resources`, `/api/eacl/lookup-subjects`, `/api/eacl/read-relationships`, and `/api/eacl/check-permission` as thin, typed adapters over the corresponding EACL operations.
- `GET /api/schema` and `PUT /api/schema` for the committed Spice source, derived graph, and schema writes.
- `GET /api/cache` and `POST /api/cache/evict` for an on-demand cache snapshot and eviction.
- `POST /api/seed` for appending benchmark-shaped demo data.

Query requests use JSON strings for EACL types, ids, relations, and permissions; the server converts only validated schema-known values to keywords. Page size is restricted to `10`, `20`, `50`, `100`, `250`, `500`, or `1000`, cursors remain opaque, request bodies are bounded, and output contains only JSON-safe view data.

Successful responses use `{ "data": ..., "meta": { "revision": ..., "elapsedMs": ..., "cacheStatus": ... } }`. Pagination returns EACL `pageInfo` without exposing internal cursor data. Failures use `{ "error": { "code": ..., "message": ..., "details": ... }, "meta": { "revision": ... } }` with stable status mappings: invalid input `400`, invalid or stale cursors `409`, invalid schema `422`, and sanitized unexpected failures `500`.

An operation-oriented API was chosen over one generic query endpoint because it permits narrow validation, useful HTTP integration tests, stable error codes, and per-operation observability. Returning preassembled whole-screen payloads was rejected because it would serialize independent panel work and cause broad refetches.

### 4. Use a composite invalidation revision

API metadata will contain an opaque revision derived from the current Datomic basis and an in-process cache generation. Schema writes advance the Datomic component; eviction advances the cache component. Those mutation responses return the new revision only after the operation has completed successfully. Seeding is the deliberate exception: `POST /api/seed` atomically reserves the single seed slot and returns `202` before a background worker starts. While that reservation is active, `GET /api/seed` exposes progress with the current composite revision, so each committed Datomic batch can invalidate visible queries before the entire job finishes.

The client stores the newest observed mutation revision and includes it in dependent resource source keys. This provides deterministic refetch after application mutations; bounded polling runs only while a seed reservation is active. The value is an invalidation token, not a user-editable Datomic coordinate, and the server does not trust revisions supplied by clients.

### 5. Coordinate remote state with native Solid primitives

Top-level signals will hold the active subject, permission, selected resource, page size, cache mode, theme, and latest mutation revision. Resource-group components own cursor stacks and `createResource` instances whose sources are stable tuples of only the inputs they actually use. Count resources exclude page cursors, nested relationship resources exist only while expanded, and selected-resource detail resources exist only when a resource is selected.

Changing subject, permission, page size, schema revision, or cache mode resets affected cursor stacks before refetch. Changing only a page cursor leaves the independent count resource intact. The stats line gives the page range/timing and exact count/timing separate Solid-owned DOM fragments so a page refresh cannot replace or flash the stable count fragment. Request helpers use `AbortController` and request identities so superseded responses cannot overwrite current state. `<For>` keys use stable type/id or permission identities, `<Show>` isolates conditional branches, and `createMemo` handles pure projections. This is preferred to a global mutable store or an additional server-state library because native Solid resources already provide the needed dependency tracking with less machinery.

### 6. Treat cache metrics as a manually captured snapshot

The cache section retains cache enablement and eviction controls and adds **Refresh cache**. Its displayed snapshot lives in a dedicated signal that is written only by the refresh handler after `GET /api/cache` succeeds. Initial render, expansion, authorization queries, cache-mode changes, schema writes, seeding, and eviction never fetch or replace that signal. The UI labels the capture time and formats the snapshot deterministically with two-space-indented JSON in `<pre><code>`.

Eviction still advances the general mutation revision so authorization queries can rerun against an empty cache, but the visible cache snapshot intentionally remains the last captured value until the next refresh. This avoids both the polling behavior in `eacl-explorer` and the expansion/eviction-coupled metrics behavior in `electric-eacl`.

### 7. Make schema writing transactional from the user's perspective

`GET /api/schema` returns committed Spice text plus graph nodes, links, and counts. The Solid schema component maintains a separate draft, offers the reference presets, renders dirty/writing/error states, and lazy-loads the graph implementation only when expanded.

`PUT /api/schema` calls EACL's schema writer and reports parse or validation errors without changing the committed schema. On success, the client adopts the returned canonical source and revision, clears authorization cursors, refreshes schema/bootstrap resources, normalizes a permission that no longer exists, and refetches visible authorization branches. D3 graph effects are isolated behind a component lifecycle with cleanup instead of relying on a page-global renderer.

### 8. Preserve the visual language without preserving Rum/Electric structure

The client will begin from `eacl-explorer/resources/public/index.css`, retain its tokens, typography, panels, badges, theme selectors, responsive breakpoints, and accessible focus states, and merge the page-size classes from `electric-eacl`. Components will emit equivalent class names while using semantic Solid JSX, labels, buttons, regions, live status text, and keyboard-operable disclosure controls.

CSS is copied into this standalone demo rather than imported across repositories so builds remain reproducible. Component boundaries follow reactive ownership—header, subjects, resource group/tree, resource detail, schema, cache, seed progress—rather than mechanically translating Rum functions.

### 9. Keep payloads and recomputation bounded

The server will call EACL's authenticated pagination and exact count operations directly, never enumerate a complete result set to paginate it in the client, and never serialize Datomic entities. Each expanded resource group performs one page query and one independent exact-count query; the UI renders the total once alongside the page range and labels both timings without duplicating the count. Relationship children load only when expanded; metadata that changes only with schema revision can be memoized on the server. Blocking Datomic/EACL work runs off the client and is instrumented per operation.

Nested relationship pages are a sweep of distinct child resources. Their per-child permission decisions therefore bypass completed-answer caching, as recommended by EACL for non-repeating batch checks. This keeps recursive point authorization target-anchored instead of computing a complete forward denotation that scales with every reachable resource and holds the EACL schema read lock for the duration. The bounded relationship enumeration itself may still honor the requested cache mode; the combined authorization-filtered operation reports cache status as disabled.

The graph bundle is lazy, production assets are compressed/cacheable, and the client keeps previous unrelated panel data during focused refetches. A repeatable 10,000-server benchmark will verify bounded response sizes and a documented local warmed-cache p95 budget of 250 ms for default-page authorization requests; CI functional tests will avoid hardware-sensitive absolute timing assertions.

### 10. Verify boundaries at multiple levels

Backend unit tests cover decoding, validation, status mapping, lifecycle, and response shaping. Datomic-memory integration tests exercise real EACL lookup, count, relationship, permission, cache, schema, cursor, and seed flows through Ring handlers. Frontend tests use Vitest and Solid Testing Library with a controllable fetch adapter to verify resource keys, cursor resets, stale-response suppression, error states, and the cache snapshot invariant. Playwright covers the three-panel journey, page-size changes, schema write success/failure, cache eviction/refresh behavior, responsive layout, and keyboard access.

## Risks / Trade-offs

- **[Risk] HTTP refetching can duplicate expensive work when inputs change rapidly** → Abort superseded client requests, key resources narrowly, debounce only free-text inputs, and keep server operations paginated and independently observable.
- **[Risk] Aborting fetch does not guarantee already-running Datomic work stops** → Enforce input and page bounds, configure server request timeouts, and measure cancellation-heavy flows so abandoned work remains bounded.
- **[Risk] A long EACL authorization read can delay `write-schema!` while it holds the schema read lock** → Keep application sweeps bounded and bypass complete-answer caching for distinct point checks. This is writer starvation rather than a cyclic deadlock; liveness for other unexpectedly long EACL reads remains an EACL-level concern.
- **[Risk] A cursor becomes invalid after subject, permission, page size, schema, or data changes** → Scope cursor stacks to the complete query identity, clear them before invalidation, and return a stable `invalid-cursor` conflict for recovery to page one.
- **[Risk] Global cache eviction affects concurrent demo users** → Document the demo-wide scope, serialize eviction, and avoid presenting this administrative endpoint as a tenant-safe production design.
- **[Risk] Schema and seed endpoints are dangerous on a network-exposed server** → Treat the default all-interface bind as trusted-LAN development only, document the loopback override and firewall requirement, cap payloads, sanitize errors, and state that production authorization is out of scope.
- **[Risk] Concurrent seed submissions can race before background execution begins** → Reserve the seed slot with one compare-and-set in the request handler, reject losers with `409`, and clear the reservation from the worker's `finally` path.
- **[Risk] Copying reference CSS can drift** → Record the source revision in documentation, keep a small intentional override section, and validate core screenshots at desktop and mobile widths.
- **[Trade-off] Native `createResource` requires custom invalidation and cancellation code** → Centralize those rules in a small typed API/resource helper and test them directly; this keeps the reference idiomatic and avoids a second state abstraction.
- **[Trade-off] No server push means unrelated external Datomic writes are not immediately visible** → State this boundary clearly and provide deterministic refresh through application mutations and full page reload.

## Migration Plan

1. Scaffold the server and client independently, pin dependencies, and add local configuration examples without secrets.
2. Implement and integration-test Datomic/EACL lifecycle plus `/api/*` contracts before connecting UI components.
3. Build the Solid explorer incrementally against mocked contracts, then run browser flows against the real durable development transactor while keeping isolated backend tests in memory.
4. Add the production client build to server resources, health checks, benchmark script, setup/API documentation, and a clean-start smoke test.
5. Release as a new standalone demo. No existing application or data migration is required; rollback consists of stopping/removing this service, while an explicitly configured durable Datomic database remains independently recoverable.

## Open Questions

None block implementation. Exact dependency versions and the production HTTP adapter will be pinned during scaffolding after confirming compatibility with the active JDK and EACL checkout.
