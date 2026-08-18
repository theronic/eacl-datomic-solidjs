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
Each group requests at most `countLimit: 30000` and renders a truncated result
as `30,000+`; exact million-row counts are intentionally outside the interactive
request budget. EACL objects cross the HTTP boundary only as
`{ "type": ..., "id": ... }`; readable names are derived locally by SolidJS.
Relationship children load only when expanded. Schema graph D3 code is a lazy
chunk. Hashed JS/CSS assets are pre-gzipped and served immutable.

After a resource is selected, the right-hand Detail panel starts with **Can
active subject?**. It sends one authoritative `/api/eacl/check-permission`
request for every permission defined on that resource type, so the current
`view` and `admin` rows are schema-derived rather than hard-coded. Each row
shows Allowed or Denied beside that point check's server-reported elapsed time
and cache provenance (`hit`, `miss`, or `disabled`). These decisions are not
inferred from the bounded permission-holder pages below them. A row retains its
last successful result while a semantic replacement is visibly refreshing,
and failures and retries remain isolated to that permission.

Seeding is asynchronous and does not replace or disable the explorer. The POST
handler uses compare-and-set to reserve the single seed slot before queueing the
worker, returns `202`, and rejects concurrent submissions with `409`. Solid polls
`GET /api/seed` only while that reservation is active; each newly committed
Datomic basis invalidates visible EACL resources so authorization remains
queryable and visibly reactive during the load. The worker releases the
reservation from `finally`, whether it succeeds or fails.

The explorer retains its last successful page while a replacement request is
running, but every retained resource, count, and relationship page is scoped to
the exact subject, permission, cache mode, page size, and mutation revision
that produced it. A subject or permission switch therefore cannot display the
previous scope's data. Browser requests have a 35-second end-to-end deadline;
the server gives EACL 30 seconds and admits at most four concurrent traversals.
Jetty propagates timeout and upstream socket-close events into the same EACL
cancellation token, interrupting a blocking read as a bounded backstop. Its
worker pool and admission queue are bounded independently, so abandoned work
cannot silently accumulate behind those four traversal permits.

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

Example authoritative point decision (the Detail panel uses this contract):

```bash
curl -s http://127.0.0.1:8088/api/eacl/check-permission \
  -H 'content-type: application/json' \
  -d '{
    "subject":{"type":"user","id":"user-1"},
    "resource":{"type":"account","id":"account-0"},
    "permission":"admin",
    "cache":true
  }'
```

Supported page sizes are `10`, `20`, `50`, `100`, `250`, `500`, and `1000`;
the default is `20`. Invalid input is `400`, stale/mismatched opaque cursors
are `409`, traversal-limit failures are `422`, saturated authorization capacity
is `503`, EACL deadlines are `504`, invalid Spice source is `422`, and
unexpected details are hidden behind a generic `500`.

Count requests require a positive `countLimit` no larger than the configured
one-million ceiling. The Explorer sends `30000`:

```bash
curl -s http://127.0.0.1:8088/api/eacl/count-resources \
  -H 'content-type: application/json' \
  -d '{
    "subject":{"type":"user","id":"super-user"},
    "resourceType":"server",
    "permission":"view",
    "countLimit":30000,
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
| `EACL_SOLIDJS_MAX_COUNT_LIMIT` | `1000000` | Maximum accepted demand-bounded count |
| `EACL_SOLIDJS_MAX_EACL_CONCURRENCY` | `4` | Concurrent EACL traversals before fast `503` rejection |
| `EACL_SOLIDJS_CACHE_MAX_ENTRIES` | `512` | Cursor/continuation and cache-admission entry bound |
| `EACL_SOLIDJS_CACHE_PROJECTION_MAX_WEIGHT` | `4194304` | Projection cache weight budget |
| `EACL_SOLIDJS_CACHE_DENOTATION_MAX_WEIGHT` | `4194304` | Denotation cache weight budget |
| `EACL_SOLIDJS_CACHE_ANSWER_MAX_WEIGHT` | `16777216` | Completed-answer cache weight budget |
| `EACL_SOLIDJS_CACHE_MANAGED_PROOF_MAX_ATOMS` | `256` | Managed proof dependency bound |
| `EACL_SOLIDJS_JETTY_MIN_THREADS` | `2` | Minimum Jetty worker threads |
| `EACL_SOLIDJS_JETTY_MAX_THREADS` | `16` | Maximum Jetty worker threads |
| `EACL_SOLIDJS_JETTY_MAX_QUEUED_REQUESTS` | `64` | Bounded Jetty admission queue |
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
managed mutation path, then warms and records 50 samples independently for
`check-permission`, `lookup-resources`, and `lookup-subjects`. Every operation
reports request count, total/average response bytes, cache-provenance counts,
and p50/p95/max for two different clocks:

- `server-latency-ms` is the API's `meta.elapsedMs`, which surrounds the
  server-side EACL adapter call and its local admission/token setup.
- `ring-boundary-latency-ms` surrounds direct invocation of the in-process Ring
  handler. It adds routing, validation, envelope creation, and JSON encoding,
  but it is **not** browser-observed HTTP latency and excludes sockets, proxying,
  network transfer, JSON parsing in the browser, scheduling, and rendering.

The warmed `lookup-resources` Ring-boundary p95 target remains ≤ 250 ms; no
absolute target is assigned to the other operations. Cache status is retained
for every sample so a warmed hit distribution cannot be mistaken for miss or
cache-disabled cost.

Reference run on 2026-08-18 (isolated memory Datomic, 10,048 total servers,
4.99 s seed, 3 warmups and 50 recorded cache hits per operation):

| Operation | Average bytes | Server p50 / p95 / max | Ring p50 / p95 / max |
| --- | ---: | ---: | ---: |
| `check-permission` | 148.88 | 0.40 / 0.55 / 0.61 ms | 1.03 / 1.41 / 1.56 ms |
| `lookup-resources` | 4,219.68 | 0.96 / 1.41 / 2.10 ms | 1.49 / 2.30 / 2.89 ms |
| `lookup-subjects` | 3,975.48 | 0.71 / 1.00 / 1.20 ms | 1.19 / 1.57 / 1.69 ms |

The `lookup-resources` target passed. This benchmark is a repeatable local
comparison, not a claim about the one-million-entity durable demo: it does not
reproduce that database's size, peer/cache history, browser/network path,
concurrent load, or worst-case recursive relationship depth. Consumers who
need those costs must run a separate deployment-level workload with stated
fixture topology and independent hit, miss, disabled, sequential, and
concurrent distributions; those hardware-sensitive timings do not belong in
the functional suite.

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
