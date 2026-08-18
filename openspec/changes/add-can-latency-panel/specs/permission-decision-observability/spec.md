## Purpose

Make point authorization decisions, latency, and cache provenance visible beside resource permission-holder lookups, and provide repeatable latency measurements for the explorer's core EACL query operations.

## ADDED Requirements

### Requirement: Authoritative point-decision source
The decision section SHALL obtain each result from the existing `POST /api/eacl/check-permission` adapter over `eacl/check-permission`, and SHALL use its authoritative Boolean decision, elapsed time, and cache provenance rather than deriving permission from lookup membership or browser logic.

#### Scenario: Check a valid permission
- **WHEN** the decision section submits a schema-valid subject, permission, resource, and cache mode
- **THEN** it receives `{ "allowed": true|false }` with `meta.elapsedMs` and `meta.cacheStatus` from `/api/eacl/check-permission`

#### Scenario: Do not infer a decision from holders
- **WHEN** a subject appears in or is absent from a visible `lookup-subjects` page
- **THEN** the point decision remains the result returned by `eacl/check-permission` and is not inferred from that bounded page

#### Scenario: Point check fails during execution
- **WHEN** the existing point-check request times out, is cancelled, or fails
- **THEN** the section presents the sanitized API error without exposing internal details or replacing unrelated results

### Requirement: Per-permission decision section
The selected-resource sidebar SHALL render a decision section above its `lookup-subjects` permission-holder sections and SHALL check the active subject against the selected resource independently for every permission defined for that resource type, including `:view` and `:admin` when both are present.

#### Scenario: Inspect an account with view and admin permissions
- **WHEN** an account is selected while the active schema defines `view` and `admin` for accounts
- **THEN** the decision section appears before the permission-holder lists and displays separate allowed-or-denied results for `:view` and `:admin`

#### Scenario: Inspect a type with a different permission set
- **WHEN** the selected resource type has a schema-defined permission set other than `view` and `admin`
- **THEN** the section checks and displays exactly that permission set rather than a hard-coded list

#### Scenario: No resource is selected
- **WHEN** no resource is selected
- **THEN** no point-authorization request runs and the existing prompt to select a resource remains visible

### Requirement: Visible and independent point-check latency
Each permission decision SHALL display the server-reported `eacl/check-permission` elapsed time and cache provenance next to its allowed-or-denied result and SHALL manage pending, refreshing, error, retry, and prior-success state independently from other permissions and from the existing `lookup-subjects` branches.

#### Scenario: Point checks succeed
- **WHEN** point checks for the visible permissions complete successfully
- **THEN** each row displays its own decision, elapsed time, and hit, miss, or disabled provenance without attributing `lookup-subjects` timing to the decision

#### Scenario: One permission check fails
- **WHEN** one permission check fails while another succeeds
- **THEN** only the failed row shows an actionable retry state while the successful decision and all permission-holder results remain usable

#### Scenario: A populated decision refreshes
- **WHEN** a previously successful point check is refetched
- **THEN** the row indicates focused refresh while retaining the prior result until the replacement succeeds

### Requirement: Reactive and bounded point checks
Visible point checks SHALL refresh when their semantic inputs change, SHALL suppress superseded responses, and SHALL not refetch for pagination-only or active-permission changes that do not alter the checked tuples.

#### Scenario: Change the checked subject or resource
- **WHEN** the active subject, selected resource, cache mode, data revision, or schema revision changes
- **THEN** each currently schema-valid permission receives one replacement point check for the new tuple

#### Scenario: Change unrelated explorer state
- **WHEN** the page size, a lookup cursor, or the active resource-list permission changes while the subject, selected resource, permission set, cache mode, and revision remain unchanged
- **THEN** the visible point decisions do not refetch

#### Scenario: A superseded request finishes late
- **WHEN** an older point check completes after its subject, resource, permission, or revision has changed
- **THEN** its response does not replace the decision for the current tuple

### Requirement: Comparable authorization latency report
The opt-in benchmark SHALL measure `check-permission`, `lookup-resources`, and `lookup-subjects` separately against the same benchmark-shaped fixture and SHALL report the sample count, response bytes, cache provenance, and p50, p95, and maximum server-reported and HTTP-boundary latency for each operation.

#### Scenario: Run the authorization benchmark
- **WHEN** the benchmark completes its warm-up and configured sample count for the three operations
- **THEN** its result contains distinct statistics keyed by `check-permission`, `lookup-resources`, and `lookup-subjects` so point-check and lookup latencies cannot be conflated

#### Scenario: Distinguish EACL work from HTTP overhead
- **WHEN** a benchmark sample completes successfully
- **THEN** the report records the response's server-reported `meta.elapsedMs` separately from the elapsed HTTP-boundary sample and retains its cache status

#### Scenario: Evaluate performance targets
- **WHEN** benchmark results are compared with documented local targets
- **THEN** target pass or failure is reported per applicable operation and hardware-sensitive absolute timing remains outside the normal functional test suite
