# EACL Datahike Demo Explorer

A SolidJS explorer for EACL v8 backed by Datahike. The Clojure server owns all
authorization work, schema, caching, opaque cursors, and storage. The browser
only receives bounded JSON pages and counts.

The application resolves the Maven adapter coordinate
`dev.eacl/eacl-datahike` version `8.0.0-SNAPSHOT`; it has no source-checkout or
legacy database dependency. The deployed build uses the exact locally installed
PR 115 snapshot because the current Clojars timestamp predates cooperative
cancellation. Its source commit and checksums are recorded in
[`docs/dependencies.md`](docs/dependencies.md).

## Local quickstart

Requirements:

- JDK 26 or newer,
- Clojure CLI,
- Node.js 22.22.2, 24.15, 26, or newer and npm.

Install the client once, then start the default in-memory Datahike server and
Vite client in separate terminals:

```bash
npm run install:client
npm run dev:server
npm run dev:client
```

Open <http://127.0.0.1:5173>. The server listens on
<http://127.0.0.1:8088>; Vite proxies `/api` to it. On first start the server
installs the demo schema and 48 permissioned server resources. No external
database, credentials, or transactor is required.

For a durable local store, use a stable UUID and an absolute writable path:

```bash
export EACL_DATAHIKE_DEMO_STORE_BACKEND=file
export EACL_DATAHIKE_DEMO_STORE_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
export EACL_DATAHIKE_DEMO_STORE_PATH="$PWD/.datahike"
npm run dev:server
```

Keep the same store ID for reconnects. Do not reuse a store ID for another
database.

For a complete S3-compatible local environment, follow the
[MinIO runbook](docs/local-minio-runbook.md). It starts a loopback-only,
persistent MinIO container and runs the real application against it.

## REPL workflow

Start a persistent loopback nREPL:

```bash
npm run dev:repl
clj-nrepl-eval --discover-ports
```

Start or restart HTTP and reload changed namespaces without replacing the JVM:

```bash
clj-nrepl-eval -p PORT \
  "(do (require '[dev :as dev] :reload) (dev/restart-backend! {:port 8088}))"
```

Run the backend suite through that REPL:

```bash
clj-nrepl-eval -p PORT --timeout 180000 \
  "(do (require '[dev :as dev] :reload) (dev/run-tests!))"
```

The application refuses to bind nREPL anywhere except `127.0.0.1`. Production
access uses an SSH tunnel:

```bash
ssh -L 7888:127.0.0.1:7888 ubuntu@HOST
clj-nrepl-eval -p 7888 "(+ 20 22)"
```

## Tests and build

```bash
npm run lint
npm run test:client
npm run test:server
npm run build
```

The production build places Vite assets under the `/datahike/` base, embeds
them in `server/target/eacl-datahike-demo.jar`, and writes a SHA-256 sidecar.
Run the real browser suite with both `npm run dev:server` and
`npm run dev:client` active:

```bash
cd client && npx playwright install chromium && cd ..
EACL_DATAHIKE_DEMO_E2E_URL=http://127.0.0.1:5173 npm run test:e2e
```

The production assets use an absolute `/datahike/` base and are exercised
through the reverse proxy contract; a raw Jetty URL does not strip that public
prefix.

## API and safety bounds

The main endpoints are:

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/health`, `/api/bootstrap` | Readiness and bounded metadata |
| `GET` | `/api/subjects`, `/api/schema`, `/api/cache`, `/api/seed` | Read-only demo state |
| `POST` | `/api/eacl/lookup-resources` | Authorized resource page |
| `POST` | `/api/eacl/count-resources` | Demand-bounded resource count |
| `POST` | `/api/eacl/lookup-subjects` | Authorized subject page |
| `POST` | `/api/eacl/read-relationships` | Relationship page |
| `POST` | `/api/eacl/check-permission` | Point permission decision |
| `PUT` | `/api/schema` | Administrative schema update |
| `POST` | `/api/cache/evict`, `/api/seed` | Administrative maintenance |

Production administrative mutations require the exact bearer token and are
also denied by the public reverse proxy. The public UI obtains capability flags
from bootstrap and hides those controls. Use loopback HTTP through SSH for an
authorized administrative request.

Defaults deliberately bound public work: 64 KiB JSON bodies, 1,000,000 count
and seed ceilings, a 30-second EACL deadline, four concurrent EACL operations,
16 Jetty threads, and 64 queued requests. Revisions are opaque strings of the
form `h<commit>.c<cache-generation>`.

The explorer assumes every HTTP request can be slow, disconnected, malformed,
or rejected. Client requests have a 35-second end-to-end deadline and
superseded requests are aborted. Initial and replacement reads always render a
labeled loading state. A replacement read keeps the last successful data and
committed page number visible, disables duplicate navigation, and shows a
spinner inside the initiating button. The new page number and results publish
together only after success. Failures retain that last successful view beside
a named error and Retry action; expired EACL cursors additionally offer First
Page recovery. Mutation controls report their in-flight state and keep
user-owned drafts or captured snapshots visible until a successful operation
invalidates them.

The EACL v8 cache uses explicit weighted tiers rather than the removed
`remember-answers` setting. Defaults retain up to 16 MiB of completed answers,
4 MiB each of projections and denotations, 256-atom managed proofs, and 512
client-private page/cursor/continuation records. `GET /api/cache` reports tier
weights, entries, evictions, oversized rejections, proof failures, continuation
use, and per-operation HTTP hit/miss/disabled counts. Tune the corresponding
`EACL_DATAHIKE_DEMO_CACHE_*` variables from those counters; do not enlarge a
tier merely because a cold computation times out before it can publish.
In production, the server starts a bounded background prewarm for the
canonical super-user 20-server page and 50,000-item count after Jetty becomes
ready. Its `running`, `complete`, `cancelled`, or `error` state is included in
`GET /api/cache`; shutdown cooperatively cancels the in-flight warmup.

## Production S3 configuration

Production uses a private S3 bucket in `us-east-1` and the EC2 instance-role
credential chain. Do not place AWS access keys in the environment file.

Required variables:

```bash
EACL_DATAHIKE_DEMO_MODE=production
EACL_DATAHIKE_DEMO_HOST=127.0.0.1
EACL_DATAHIKE_DEMO_PORT=8088
EACL_DATAHIKE_DEMO_STORE_BACKEND=s3
EACL_DATAHIKE_DEMO_STORE_ID=<stable-uuid>
EACL_DATAHIKE_DEMO_S3_BUCKET=<globally-unique-private-bucket>
EACL_DATAHIKE_DEMO_S3_REGION=<aws-region>
EACL_DATAHIKE_DEMO_SECURITY_KEY=<at-least-32-random-characters>
EACL_DATAHIKE_DEMO_ADMIN_TOKEN=<different-at-least-32-random-characters>
EACL_DATAHIKE_DEMO_NREPL_PORT=7888
EACL_DATAHIKE_DEMO_SEED_TRANSACTION_SIZE=250
EACL_DATAHIKE_DEMO_SEED_PAUSE_MS=50
EACL_DATAHIKE_DEMO_CACHE_MAX_ENTRIES=512
EACL_DATAHIKE_DEMO_CACHE_PROJECTION_MAX_WEIGHT=4194304
EACL_DATAHIKE_DEMO_CACHE_DENOTATION_MAX_WEIGHT=4194304
EACL_DATAHIKE_DEMO_CACHE_ANSWER_MAX_WEIGHT=16777216
EACL_DATAHIKE_DEMO_CACHE_MANAGED_PROOF_MAX_ATOMS=256
```

The store ID, signing key, and admin token must remain stable across restarts.
Changing the signing key invalidates cursors and EACL-managed values. The
systemd environment file is root-owned mode `0600`; logs and API errors never
include raw storage configuration or credentials.

Caddy exposes the configured hostname under `/datahike/`, redirects `/`, strips
the prefix before proxying to loopback Jetty, and denies public schema writes,
seed starts, and cache eviction. The pre-cutover Caddy configuration is
HTTP-only so certificate issuance does not run before DNS points at the
instance. See the [production runbook](docs/deployment.md) for the complete,
parameterized AWS procedure and capacity gates.

## Seed, recovery, and operations

Always accept the 48-resource fixture first. Seed jobs are asynchronous,
single-flight, and pace configurable 250-item transactions within logical
2,000-server account batches. Poll
`GET /api/seed`; the explorer remains readable between commits. Do not begin a
large seed until small-fixture API, browser, restart, and S3 persistence checks
pass.

Permanent sizing is based on a clean JVM after the seed, not peak loading
memory. The local two-process durable-store preflight and the mandatory
one-million-resource production acceptance gates are recorded in
[`docs/read-sizing.md`](docs/read-sizing.md). A temporary loading resize, if it
is ever needed, requires separate operator cost approval and is reverted before
read acceptance.

Operational files live under `infra/` after provisioning. Common commands are:

```bash
sudo systemctl status eacl-datahike-demo caddy
sudo journalctl -u eacl-datahike-demo -n 200 --no-pager
curl --fail http://127.0.0.1:8088/api/health
```

Rollback redeploys the preceding checksummed jar and restarts the service; it
does not delete the retained S3 bucket. DNS rollback restores the captured
stale A record only if publication fails. Teardown removes the CloudFormation
stack after explicitly preserving or emptying the retained versioned bucket;
never delete that bucket as part of an application rollback.

## Project layout

- `client/` — SolidJS UI, unit tests, and Playwright flows.
- `server/` — Clojure API, Datahike/EACL integration, tests, and uberjar build.
- `docs/` — dependency, verification, storage, sizing, and source-port evidence.
- `infra/` — deployment, proxy, service, validation, and rollback artifacts.
- `openspec/` — historical specifications copied with the source project; the
  active deployment change is tracked in the parent EACL workspace.
