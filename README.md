# EACL SolidJS Explorer

A fast, conventional HTTP reference for EACL v8. Clojure, Datomic Pro, the
EACL client, cache, schema, and opaque cursors stay on the server. An idiomatic
SolidJS application renders bounded, reactive `/api/*` results.

The information architecture and attributed stylesheet come from
`eacl-explorer`; the Datomic fixture shape and page-size selector come from
`electric-eacl`. Neither runtime is coupled to this application.

## Quickstart

Requirements: JDK 22, Clojure CLI, Node.js 22, npm, Datomic Pro installed at
`~/datomic/1.0.7705`, and access to Datomic Pro dependencies. This workspace
version uses the EACL checkout at
`../eacl` (commit `45be4cc3b94c876fe2395afc992bff1cf8f03676`) so its generated formal
kernel classes remain available.

Start the Datomic `:dev` transactor in its own terminal. Its embedded H2 data is
stored under `~/datomic/1.0.7705/data` and survives application/JVM restarts:

```bash
cd ~/datomic/1.0.7705
bin/transactor config/samples/dev-transactor-template.properties
# Wait for: System started
```

Then start the application in another terminal. Keep the EACL key stable across
restarts because it signs opaque cursors and managed EACL values; store the real
value in a local secret manager or shell profile, never in this repository.

```bash
export EACL_SOLIDJS_SECURITY_KEY='replace-with-your-stable-local-key'
npm run install:client
npm run build
cd server
clojure -M:run
```

Open <http://127.0.0.1:8088> locally or
`http://YOUR-MAC.local:8088/` from another device on the same trusted Wi-Fi.
The production build and API share one origin. The default database is a fresh
durable `datomic:dev://localhost:4334/eacl-solidjs` database with 48 foundation
servers on first creation. Schema edits and later seed data persist when the app
or REPL restarts as long as the H2 transactor data directory is retained.

For split development, start the server through the REPL workflow below, then
run `npm run dev:client` from the project root. Vite proxies `/api` to port
8088 and hot-reloads SolidJS.

## Architecture

```text
Browser / SolidJS
  ├─ native signals, memos, resources, keyed lists
  ├─ AbortController + latest-request suppression
  └─ JSON only: /api/*
                 │
Ring / Reitit HTTP boundary
  ├─ bounded validation and stable envelopes
  ├─ opaque cursor/status mapping and request ids
  └─ operation duration, bytes, cache provenance
                 │
EACL Datomic client (managed coherence)
  ├─ authenticated server-side pagination and exact counts
  ├─ schema writer and cache provider
  └─ Datomic Pro peer connection
```

Every authorization query is server-owned. The client never downloads the
complete dataset, never receives Datomic entities, and never bundles EACL,
DataScript, Rum, or Electric. Resource pages and exact counts refetch
independently, but each group issues exactly one count request and renders that
total once beside its page range. EACL objects cross the HTTP boundary only as
`{ "type": ..., "id": ... }`; readable names are derived locally by SolidJS.
Relationship children load only when expanded. Schema graph D3 code is a lazy
chunk. Hashed JS/CSS assets are pre-gzipped and served immutable.

Seeding is asynchronous and does not replace or disable the explorer. The POST
handler uses compare-and-set to reserve the single seed slot before queueing the
worker, returns `202`, and rejects concurrent submissions with `409`. Solid polls
`GET /api/seed` only while that reservation is active; each newly committed
Datomic basis invalidates visible EACL resources so authorization remains
queryable and visibly reactive during the load. The worker releases the
reservation from `finally`, whether it succeeds or fails.

The cache display has a stricter rule than the other panels: opening it,
running queries, toggling caching, evicting, writing schema, or seeding does
not fetch or replace its displayed JSON. Only **Refresh cache** captures a new,
two-space-indented snapshot; a failed refresh retains the last successful one.

## REPL workflow and tests

Discover a running server:

```bash
clj-nrepl-eval --discover-ports
```

If needed, start one from `server/`:

```bash
clojure -M:dev:nrepl
```

Start or restart HTTP without restarting the JVM:

```bash
clj-nrepl-eval -p PORT "(do (require '[dev :as dev] :reload) (dev/restart-backend! {:port 8088}))"
```

Run all backend tests through the persistent nREPL (never a cold test JVM):

```bash
clj-nrepl-eval -p PORT --timeout 180000 "(do (require '[dev :as dev] :reload) (dev/run-tests!))"
```

Client and browser verification:

```bash
npm run lint
npm run test:client
npm run build
# Start the real backend first; override its URL if it is not on 8089.
EACL_SOLIDJS_E2E_URL=http://127.0.0.1:8089 npm run test:e2e
```

The Playwright suite runs the same real Datomic-backed flows at desktop and
narrow mobile viewports. Install its browser once with
`cd client && npx playwright install chromium`.

## API contracts

Successes use a common envelope. EACL query responses also contain elapsed
time and cache provenance (`hit`, `miss`, or `disabled`):

```json
{
  "data": { "items": [], "pageInfo": { "hasNextPage": false } },
  "meta": {
    "revision": "d1132.c0",
    "requestId": "4e47b9f1-...",
    "elapsedMs": 3.72,
    "cacheStatus": "hit"
  }
}
```

Errors are sanitized and stable:

```json
{
  "error": {
    "code": "invalid-cursor",
    "message": "The supplied cursor does not match this query."
  },
  "meta": { "revision": "d1132.c0", "requestId": "4e47b9f1-..." }
}
```

Main routes:

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/health`, `/api/bootstrap` | Readiness and bounded initial metadata |
| `GET` | `/api/subjects?offset=0&limit=20` | Deterministic known-user page |
| `POST` | `/api/eacl/lookup-resources` | Authorized resource page with opaque `after` cursor |
| `POST` | `/api/eacl/count-resources` | Independent exact authorized count |
| `POST` | `/api/eacl/lookup-subjects` | Permission holders for one resource |
| `POST` | `/api/eacl/read-relationships` | Bounded child traversal, optionally authorization-filtered |
| `POST` | `/api/eacl/check-permission` | Authoritative allowed/denied decision |
| `GET`, `PUT` | `/api/schema` | Read or validate/commit Spice source |
| `GET` | `/api/cache` | Side-effect-free provider snapshot |
| `POST` | `/api/cache/evict` | Serialized cache eviction |
| `GET`, `POST` | `/api/seed` | Progress or reserved asynchronous append-only seed work (`202`) |

Example resource query:

```bash
curl -s http://127.0.0.1:8088/api/eacl/lookup-resources \
  -H 'content-type: application/json' \
  -d '{
    "subject":{"type":"user","id":"super-user"},
    "resourceType":"server",
    "permission":"view",
    "pageSize":20,
    "cache":true
  }'
```

Supported page sizes are `10`, `20`, `50`, `100`, `250`, `500`, and `1000`;
the default is `20`. Invalid input is `400`, stale/mismatched opaque cursors
are `409`, invalid Spice source is `422`, and unexpected details are hidden
behind a generic `500`.

## Configuration

The server reads environment variables directly; `.env.example` is a
secret-free reference and is not loaded automatically.

| Variable | Default | Notes |
| --- | --- | --- |
| `EACL_SOLIDJS_HOST` | `0.0.0.0` | Trusted-LAN development; use `127.0.0.1` for local-only access |
| `EACL_SOLIDJS_PORT` | `8088` | HTTP port |
| `EACL_SOLIDJS_DATOMIC_URI` | `datomic:dev://localhost:4334/eacl-solidjs` | Durable H2-backed local database |
| `EACL_SOLIDJS_REQUEST_TIMEOUT_MS` | `30000` | HTTP request budget |
| `EACL_SOLIDJS_MAX_BODY_BYTES` | `1048576` | JSON and schema body limit |
| `EACL_SOLIDJS_MAX_SEED_SERVERS` | `100000` | Per-request seed limit |
| `EACL_SOLIDJS_SECURITY_KEY` | none | Required for the durable default; keep stable and secret |

The composite revision is `d<datomic-basis>.c<cache-generation>`. It is an
invalidation token, not a client-selected snapshot coordinate.

## Security limitations

This is a local development/reference application, not an internet-facing
admin service. It intentionally has no identity authentication, tenant
isolation, CSRF layer, or authorization around schema writes, cache eviction,
and seeding. The development default is reachable from the LAN: use it only on
a trusted network with the host firewall enabled, or set
`EACL_SOLIDJS_HOST=127.0.0.1` for local-only access. Do not put secrets in browser
state or committed config.

Durable Datomic configuration fails fast without a stable
`EACL_SOLIDJS_SECURITY_KEY`; changing that key invalidates existing cursors and
may make previously written managed EACL values unreadable.

## Benchmark

The benchmark is opt-in and is not part of regular tests. From a running dev
nREPL:

```bash
clj-nrepl-eval -p PORT --timeout 900000 \
  "(do (require '[benchmark :as bench] :reload) (bench/run!))"
```

It creates an isolated memory database, appends 10,000 servers through EACL's
managed mutation path, warms the default 20-item page, then records request
count, response bytes, and p50/p95/max across 50 Ring HTTP-boundary samples.
The documented warmed default-page target is p95 ≤ 250 ms.

Reference run on 2026-08-08 (arm64, OpenJDK 22.0.1): 10,048 total servers,
4.63 s seed time, 50 requests, 4,216.84 average response bytes, 4.11 ms p50,
6.54 ms p95, and 52.01 ms max. The target passed. These numbers are a local
signal, not a hardware-stable CI assertion.

## Troubleshooting

- **Port already in use:** choose another port in `dev/restart-backend!` and set
  `EACL_SOLIDJS_E2E_URL` for browser tests.
- **Datomic dependency resolution fails:** confirm Datomic Pro credentials and
  repositories are configured for the Clojure CLI user.
- **The app cannot connect to Datomic:** start
  `~/datomic/1.0.7705/bin/transactor` with
  `config/samples/dev-transactor-template.properties` and wait for
  `System started`; ports `4334` and `4335` must be available.
- **The durable database disappeared:** confirm the transactor starts from
  `~/datomic/1.0.7705` so its relative `data` directory resolves to the existing
  H2 files; do not delete that directory.
- **EACL generated classes are missing:** use the matching sibling EACL checkout
  and build its formal Java classes at the pinned commit.
- **Cursors fail after restart:** configure a stable security key; memory-mode
  random keys are intentionally process-local.
- **A stale production page is blank or downloads:** run `npm run build`; the
  server must serve the generated HTML/JS with their MIME types.
- **Schema write returns 422:** the committed schema is still active. Fix the
  retained draft or choose a known-good preset and write again.

## Project layout

- `client/` — SolidJS, TypeScript, Vite, Vitest, Playwright, compression step
- `server/` — Clojure, Ring/Reitit, Datomic Pro, EACL, tests, benchmark
- `openspec/` — proposal, design, executable specs, and implementation tasks
