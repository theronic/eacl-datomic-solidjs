## Why

EACL needs a small, fast reference application that demonstrates Datomic Pro authorization through ordinary HTTP rather than a browser-local database or Electric's distributed runtime. A SolidJS implementation provides a conventional web architecture while preserving the reactive exploration experience and visual language of the existing demos.

## What Changes

- Create a new full-stack EACL demo in `eacl-solidjs`, with a Clojure backend owning Datomic Pro and EACL and an idiomatic SolidJS frontend.
- Expose every EACL authorization operation used by the explorer through validated JSON endpoints under `/api/*`, including resource lookup and count, subject lookup, relationship traversal, permission checks, schema reads and writes, and cache administration.
- Recreate the explorer's subject/permission selector, cursor-paginated resource tree, nested relationships, selected-resource detail, schema editor and graph, seeding controls, cache controls, timing/provenance badges, themes, responsive layout, and reference styling.
- Make authorization results reactively refetch from HTTP when their SolidJS inputs or a successful mutation revision changes, while aborting or ignoring stale responses and preserving unrelated panel state.
- Add Electric EACL's page-size selector with supported sizes `10`, `20`, `50`, `100`, `250`, `500`, and `1000`, defaulting to `20`, and reset cursor state when its value changes.
- Allow users to edit and write the Spice schema, surface validation failures without replacing the active schema, and reactively refresh schema-dependent queries after a successful write.
- Pretty-print the cache snapshot and update that display only when the user clicks a dedicated **Refresh cache** button; ordinary queries, cache toggles, expansion, and cache eviction do not silently refresh the displayed snapshot.
- Optimize the hot path with server-side EACL pagination/count APIs, bounded payloads, keyed fine-grained rendering, stable query identities, request cancellation, and focused loading states.
- Bound each initial resource total at `:count-limit 50000`, render truncated totals as `50k+`, and let an explicit click double only that group's limit until EACL reports exhaustion and the exact total.
- Add automated backend, frontend, HTTP integration, and browser-flow coverage plus setup and API documentation.

## Capabilities

### New Capabilities

- `eacl-http-api`: Datomic Pro lifecycle, demo data, validated `/api/*` contracts, EACL query execution, schema mutation, cache control, error semantics, and revision metadata.
- `solidjs-eacl-explorer`: Idiomatic SolidJS explorer behavior, reactive HTTP resource coordination, pagination and page sizing, schema editing, manual cache snapshots, reference styling, accessibility, and performance expectations.

### Modified Capabilities

None.

## Impact

- Adds a standalone application, tests, documentation, and OpenSpec source of truth under `/Users/petrus/Code/eacl-solidjs`.
- Adds frontend dependencies for SolidJS and its Vite integration, and backend dependencies for Clojure HTTP/JSON handling, Datomic Pro, and the EACL Datomic adapter.
- Introduces same-origin `/api/*` contracts and development proxy configuration; deployment must provide Datomic configuration and persistent token key material without committing credentials.
- Uses `eacl-explorer` and `electric-eacl` as behavioral and styling references only; neither reference repository nor EACL's public library API is changed.
