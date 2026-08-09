## ADDED Requirements

### Requirement: Server-owned Datomic Pro and EACL lifecycle
The server SHALL exclusively own the Datomic Pro connection, EACL Datomic client, cache store, token configuration, demo initialization, and HTTP lifecycle, and SHALL construct EACL with managed coherence after idempotently installing required storage attributes.

#### Scenario: Fresh development startup
- **WHEN** the server starts against the default H2-backed Datomic `:dev` transactor with a stable security key
- **THEN** it binds on all interfaces for trusted-LAN demo access, installs the required storage schema and demo foundation exactly once, preserves data across application restarts, constructs a usable EACL client, and reports ready through `GET /api/health`

#### Scenario: Invalid durable configuration
- **WHEN** a non-memory Datomic configuration lacks required persistent token key material
- **THEN** startup fails before the HTTP listener accepts requests and reports an actionable configuration error without inventing or logging credentials

### Requirement: Versioned and consistent API envelopes
Every `/api/*` response SHALL be JSON, SHALL use a consistent success or error envelope, and SHALL include an opaque current revision in metadata. EACL query successes SHALL also expose elapsed time and cache provenance as `hit`, `miss`, or `disabled`.

#### Scenario: Successful EACL request
- **WHEN** a valid EACL query completes
- **THEN** the response contains `data` and `meta` with `revision`, `elapsedMs`, and `cacheStatus`, represents each EACL object only by its passthrough `type` and `id`, and contains no display-name enrichment, Datomic entity, or server-internal object

#### Scenario: Failed request
- **WHEN** request validation or execution fails
- **THEN** the response contains a stable error code and safe message plus revision metadata and does not expose a stack trace, secret, or filesystem detail

### Requirement: Bootstrap and demo metadata API
The server SHALL provide `GET /api/bootstrap` with the current revision, readiness and seed status, demo totals, schema-derived resource types and permissions, quick subjects, schema presets, and supported page-size options.

#### Scenario: Client bootstraps once
- **WHEN** a client requests `GET /api/bootstrap` after startup
- **THEN** it receives enough JSON-safe metadata to render initial controls without executing EACL or reading Datomic directly in the browser

### Requirement: Bounded demo subject reads
The server SHALL provide a `/api/*` endpoint for paged known-subject identifiers with deterministic ordering and bounded inputs and outputs.

#### Scenario: Read known subjects
- **WHEN** the client requests a valid known-subject page
- **THEN** the server returns only the requested bounded page, passthrough subject type/id pairs, and continuation metadata without display-name enrichment

### Requirement: Resource lookup and demand-bounded count operations
The server SHALL expose `POST /api/eacl/lookup-resources` and `POST /api/eacl/count-resources` as thin adapters over EACL's authenticated paginated lookup and demand-bounded count operations. Count requests SHALL accept a validated positive `countLimit`, pass it to EACL as `:count-limit`, and return `count`, `limit`, and `truncated`.

#### Scenario: First authorized resource page
- **WHEN** a valid subject, permission, resource type, supported page size, and cache mode are submitted without a cursor
- **THEN** lookup returns only the authorized first page with opaque `pageInfo`, while count returns at most the requested limit and reports whether more authorized resources exist

#### Scenario: Reject an invalid count limit
- **WHEN** `countLimit` is absent, non-integral, zero, or negative
- **THEN** the server returns `400 invalid-count-limit` before invoking EACL

#### Scenario: Count reaches graph exhaustion
- **WHEN** EACL exhausts the authorized graph before the submitted count limit
- **THEN** the response reports `truncated: false` and `count` is the exact authorized total

#### Scenario: Continue an authorized resource page
- **WHEN** a client resubmits the same non-page query with the returned `endCursor`
- **THEN** the server passes the opaque cursor to EACL and returns the next authorized page without client-side full-result pagination

### Requirement: Subject lookup operation
The server SHALL expose `POST /api/eacl/lookup-subjects` for bounded lookup of subjects that hold a permission on a selected resource.

#### Scenario: Inspect resource permission holders
- **WHEN** a valid resource, permission, subject type, page size, and cache mode are submitted
- **THEN** the response contains only matching subjects, opaque pagination metadata, elapsed time, and cache provenance

### Requirement: Relationship traversal operation
The server SHALL expose `POST /api/eacl/read-relationships` for bounded, cursor-authenticated traversal of relationships used by expanded resource-tree nodes.

#### Scenario: Expand a resource node
- **WHEN** the client submits a valid parent subject, target resource type, relation, page size, and optional cursor
- **THEN** the server returns the bounded matching relationships and opaque page metadata without enumerating unrelated relationships

#### Scenario: Filter a recursive nested page
- **WHEN** a bounded relationship page is filtered by permission under a recursive schema
- **THEN** the server evaluates each distinct child with cache-bypassed target-anchored point authorization so it does not materialize a complete forward denotation or starve schema writes

### Requirement: Permission check operation
The server SHALL expose `POST /api/eacl/check-permission` and SHALL return the EACL decision with timing and cache provenance.

#### Scenario: Check a candidate child resource
- **WHEN** the client submits a schema-valid subject, permission, resource, and cache mode
- **THEN** the response reports the authoritative allowed or denied decision and contains no authorization logic evaluated by the browser

### Requirement: Strict request validation and cursor recovery
The server SHALL validate request media type, body size, field shape, string length, schema-known types and names, cache mode, supported page size, and opaque cursor errors at the HTTP boundary before invoking EACL.

#### Scenario: Unsupported page size
- **WHEN** a query requests a page size outside `10`, `20`, `50`, `100`, `250`, `500`, and `1000`
- **THEN** the server returns `400` with an `invalid-page-size` error and does not invoke EACL

#### Scenario: Cursor does not match the query
- **WHEN** EACL rejects a cursor after its subject, permission, type, page size, or revision context changed
- **THEN** the server returns `409` with an `invalid-cursor` error that allows the client to recover by returning to page one

### Requirement: Schema read and transactional write API
The server SHALL provide `GET /api/schema` for the committed Spice source, graph, and counts and `PUT /api/schema` for validated EACL schema writes. A failed write MUST leave the previously committed schema active.

#### Scenario: Read committed schema
- **WHEN** the client requests `GET /api/schema`
- **THEN** the server returns the committed source plus JSON-safe graph nodes, links, resource count, relation count, permission count, and current revision

#### Scenario: Write valid schema
- **WHEN** the client submits different valid Spice source
- **THEN** EACL commits it, the Datomic-backed revision advances, and the response returns the committed source, derived metadata, and new revision

#### Scenario: Reject invalid schema
- **WHEN** the client submits source that fails parsing or schema validation
- **THEN** the server returns `422` with field-safe diagnostics, does not advance the revision, and subsequent reads return the prior committed source

### Requirement: Explicit cache administration API
The server SHALL provide `GET /api/cache` for a current cache-provider snapshot and `POST /api/cache/evict` for demo-wide eviction. Eviction SHALL advance the cache component of the opaque revision after it completes.

#### Scenario: Read cache snapshot
- **WHEN** the client explicitly requests `GET /api/cache`
- **THEN** the server returns JSON-safe provider statistics and capture metadata without modifying the cache

#### Scenario: Evict cache
- **WHEN** the client submits `POST /api/cache/evict`
- **THEN** the server clears the configured EACL cache, advances the revision, and returns completion metadata without claiming that any previously displayed client snapshot was refreshed

### Requirement: Reserved asynchronous benchmark-shaped seeding
The server SHALL provide `POST /api/seed` to atomically reserve one background seed job and append validated benchmark-shaped demo data through EACL-managed mutation paths while exposing progress, current revision, and completion state through `GET /api/seed`.

#### Scenario: Seed additional servers
- **WHEN** a positive bounded server count is submitted while no seed job is active
- **THEN** the server compare-and-set reserves the job before queueing it, returns `202` with seeding progress, appends the requested fixture topology in committed batches, preserves existing data, and exposes revision changes that let affected queries invalidate during and after the job

#### Scenario: Query while seeding
- **WHEN** a seed worker is committing data and an EACL query arrives
- **THEN** the query executes against an available Datomic basis without waiting for the entire seed job and returns a normal bounded EACL response

#### Scenario: Reject overlapping seed jobs
- **WHEN** a second seed request arrives while a seed job is active
- **THEN** the failed compare-and-set causes a stable `409` conflict and no duplicate job is queued

#### Scenario: Release a seed reservation
- **WHEN** the background job completes or fails
- **THEN** its `finally` path releases the reservation so a later valid seed request can start

### Requirement: Bounded and observable authorization performance
The server SHALL use EACL's server-side page/count APIs, SHALL avoid serializing complete authorization result sets or Datomic entities, and SHALL record per-operation duration and outcome. The repository SHALL include a repeatable 10,000-server warmed-cache benchmark with a documented default-page p95 target of 250 ms on the reference development environment.

#### Scenario: Large fixture page request
- **WHEN** the benchmark queries the default page over a 10,000-server fixture
- **THEN** each response remains bounded by the requested page size, reports elapsed time and cache provenance, and the benchmark summarizes p50 and p95 without making hardware-sensitive timing part of the normal functional suite
