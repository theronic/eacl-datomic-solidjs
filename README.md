# EACL SolidJS Explorer

A fast, conventional HTTP reference for EACL v8. Clojure, Datomic Pro, the
EACL client, cache, schema, and opaque cursors stay on the server. An idiomatic
SolidJS application renders bounded, reactive `/api/*` results.

The information architecture and attributed stylesheet come from
`eacl-explorer`; the Datomic fixture shape and page-size selector come from
`electric-eacl`. Neither runtime is coupled to this application.

## Quickstart

Requirements:

- JDK 26 for the default published EACL artifact,
- a current Clojure CLI,
- Node.js 22 or newer and npm,
- access to the Datomic Pro dependencies.

For the quickest development start, no transactor or security key is needed.
The server script uses an in-memory Datomic database:

```bash
npm run install:client
npm run dev:server
# In another terminal:
npm run dev:client
```

The same root scripts can be invoked with pnpm, for example
`pnpm run dev:server`.

Open <http://127.0.0.1:5173>. The default server resolves
`dev.eacl/eacl-datomic` version `8.0.0-SNAPSHOT` from Clojars. That artifact
already contains the generated EACL kernel classes: dependency resolution and
application startup do not install formal tools or run verification.

### Local EACL development

To edit EACL and the application together, clone EACL beside this repository
as `core` (or adjust both the `:local/root` in `server/deps.edn` and the
`prep:local-eacl` path in `package.json`):

```text
workspace/
  core/
  eacl-datomic-solidjs/
```

The `:local-eacl` alias replaces the Clojars adapter with that checkout. Source
preparation is deliberately separate from every application-start command.

> [!WARNING]
> Run the following preparation command only when you choose local EACL source
> development. It may download EACL's checksum-verified formal toolchain,
> including Dafny/Boogie/Z3, Apalache, and TLA+ tools, plus pinned Node
> packages. It generates and stages JVM/browser runtimes and can consume
> substantial disk space and time. It does not run the full formal suite.

```bash
# Once after cloning EACL, and again after generated-kernel changes:
npm run prep:local-eacl

# Then start the local-source mode (the client command is unchanged):
npm run dev:server:local-eacl
```

To target a JVM older than 26, invoke EACL's preparation command directly with
the desired release before starting local mode, for example:

```bash
cd ../core/modules/eacl
clojure -T:build prep :java-release 22
cd ../../../eacl-datomic-solidjs
npm run dev:server:local-eacl
```

The selected class files run on that Java release and newer JVMs. The standard
Clojars artifact targets Java 26, so older-JVM consumers must use an explicitly
lower-targeted source or custom artifact build.

### IntelliJ IDEA

Install the Cursive plugin, open `eacl-datomic-solidjs`, and add
`server/deps.edn` as a Clojure Deps project if Cursive does not detect it
automatically. Select JDK 26 for published-artifact mode, or the JVM targeted
by your explicit local EACL preparation.

The shared **EACL App**, **EACL Server**, and **EACL Client** run configurations
appear under Run | Edit Configurations. Run **EACL App** to start the Clojars
server and client together. **EACL App (Local EACL)** and
**EACL Server (Local EACL)** select the `:local-eacl` alias; they never prepare
EACL automatically. After an explicit local preparation, reload the Clojure
Deps project once if IntelliJ has not indexed the newly generated Java classes.
Machine-specific `.idea` and `*.iml` files remain ignored; only the portable
run configurations under `.run/` are shared.

### Durable Datomic development

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
npm run build
npm run start:server
```

Open <http://127.0.0.1:8088> locally or
`http://YOUR-MAC.local:8088/` from another device on the same trusted Wi-Fi.
The production build and API share one origin. The default database is a fresh
durable `datomic:dev://localhost:4334/eacl-solidjs` database with 48 foundation
servers on first creation. Schema edits and later seed data persist when the app
or REPL restarts as long as the H2 transactor data directory is retained.

For split development, Vite proxies `/api` to port 8088 and hot-reloads
SolidJS.

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
  ├─ authenticated server-side pagination and demand-bounded counts
  ├─ schema writer and cache provider
  └─ Datomic Pro peer connection
```

Every authorization query is server-owned. The client never downloads the
complete dataset, never receives Datomic entities, and never bundles EACL,
DataScript, Rum, or Electric. Resource pages and counts refetch independently.
Each group starts with `countLimit: 50000`, renders a truncated result as
`50k+`, and doubles only that limit when the user clicks the count; the final
non-truncated response is exact. EACL objects cross the HTTP boundary only as
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

If needed, start one from the project root. It uses the Clojars artifact and an
in-memory Datomic database:

```bash
npm run dev:repl
```

Start or restart HTTP without restarting the JVM:

```bash
clj-nrepl-eval -p PORT "(do (require '[dev :as dev] :reload) (dev/restart-backend! {:port 8088}))"
```

Run all backend tests through the persistent nREPL (never a cold test JVM):

```bash
clj-nrepl-eval -p PORT --timeout 180000 "(do (require '[dev :as dev] :reload) (dev/run-tests!))"
```

To run Clojure directly from `server/` with the published artifact:

```bash
clojure -M:run
```

For local source mode, explicitly prepare the EACL checkout as warned above,
then run `clojure -M:local-eacl:run`. If startup reports
`CacheKernel.CacheCandidate`, the selected local checkout has not been prepared
or IntelliJ has not reloaded its classpath. If dependency resolution says that
`../../core/modules/eacl-datomic` does not exist, clone EACL as the `core`
sibling shown above or update that `:local/root` in `server/deps.edn`.

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
| `POST` | `/api/eacl/count-resources` | Independent demand-bounded authorized count |
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

Count requests require a positive `countLimit`. The Explorer sends `50000`
initially and doubles it only when the user clicks a truncated `N+` total:

```bash
curl -s http://127.0.0.1:8088/api/eacl/count-resources \
  -H 'content-type: application/json' \
  -d '{
    "subject":{"type":"user","id":"super-user"},
    "resourceType":"server",
    "permission":"view",
    "countLimit":50000,
    "cache":true
  }'
```

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
