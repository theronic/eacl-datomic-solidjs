## ADDED Requirements

### Requirement: Idiomatic SolidJS application boundary
The browser application SHALL be implemented with SolidJS JSX and native fine-grained primitives, SHALL obtain all application and EACL data through `/api/*` HTTP requests, and MUST NOT bundle Datomic, EACL, DataScript, Rum, or Electric runtime authorization logic.

#### Scenario: Load the explorer
- **WHEN** the application boots in a supported browser
- **THEN** Solid resources request bootstrap and visible panel data through `/api/*` and render without direct database or EACL access

### Requirement: Reference visual and interaction parity
The explorer SHALL preserve `eacl-explorer`'s visual tokens, typography, panels, cache/timing badges, schema section, responsive layout, light/dark themes, and three-panel information architecture while using component boundaries based on Solid reactive ownership.

#### Scenario: Desktop explorer layout
- **WHEN** the application renders at a desktop viewport
- **THEN** subjects and permissions, authorized resources, and selected-resource detail appear in the styled three-panel layout with schema and cache segments

#### Scenario: Narrow viewport
- **WHEN** the application renders at a mobile-width viewport
- **THEN** controls and panels reflow without clipped content or inaccessible actions while retaining the reference visual language

#### Scenario: Use dark theme
- **WHEN** the user selects dark theme
- **THEN** the document background, panels, cards, controls, editors, graph, and text adopt coherent dark tokens with readable contrast

### Requirement: Reactive HTTP resource coordination
Authorization resources SHALL refetch when their actual Solid source inputs change, SHALL preserve unrelated branch state, and SHALL abort or ignore superseded responses. A successful schema, seed, or cache-eviction mutation SHALL publish a revision that invalidates affected visible resources.

#### Scenario: Change active subject
- **WHEN** the user selects another subject
- **THEN** subject-dependent resource and detail queries refetch through `/api/*`, affected cursors reset, and schema and manually captured cache state remain unchanged

#### Scenario: Ignore late stale response
- **WHEN** an older request completes after a newer request for the same resource branch
- **THEN** the older result cannot overwrite the currently keyed result or selection

#### Scenario: Invalidate after mutation
- **WHEN** an application mutation succeeds with a new revision
- **THEN** only resources whose source includes that revision refetch and visible unrelated UI state is retained

### Requirement: Subject and permission selection
The subjects panel SHALL display quick subjects, a bounded known-subject page, schema-derived permissions, active selections, timing/cache provenance where applicable, and clear empty and error states.

#### Scenario: Select a known subject and permission
- **WHEN** the user selects a known subject and an available permission
- **THEN** both controls show their active state and authorized resource groups query the selected pair without resetting the independent known-subject page

#### Scenario: Render quick subjects
- **WHEN** quick-subject options are visible
- **THEN** each compact option renders its supplied label without a redundant user-type badge

#### Scenario: Written schema removes active permission
- **WHEN** a successful schema write no longer defines the active permission
- **THEN** the client selects the first remaining valid permission or an explicit no-permission state before issuing replacement queries

### Requirement: Cursor-paginated authorized resource tree
The resources panel SHALL group authorized resources by schema-derived type, fetch EACL page and demand-bounded count results independently, render stable keyed items, and support first, previous, and next navigation with opaque cursor stacks. Each count SHALL start at `countLimit: 50000`; a truncated total SHALL render with `+` as a button whose activation doubles only that group's limit until EACL reports exhaustion.

#### Scenario: Expand a resource type
- **WHEN** the user expands a resource-type group
- **THEN** the client fetches its first authorized page and one independent count bounded at 50,000, displays `range (page timing/status) of total (count timing/status)` with a truncated total such as `50k+` exactly once, and renders no more than the selected page size

#### Scenario: Increase a truncated total
- **WHEN** the user activates a truncated count such as `50k+`
- **THEN** only that count resource refetches with double its prior `countLimit`, the page and other groups remain stable, and the prior count remains visible while the request is pending

#### Scenario: Reach an exact total
- **WHEN** a count response reports `truncated: false`
- **THEN** the UI renders the exact formatted total as non-clickable text and performs no further count work without a semantic query change

#### Scenario: Reset count demand for a new query
- **WHEN** subject, permission, cache mode, data revision, or schema revision changes
- **THEN** the affected group's count limit resets to 50,000 before its replacement request, while a page-size or cursor-only change does not refetch or widen the count

#### Scenario: Collapse a resource type
- **WHEN** the user collapses a resource-type group
- **THEN** its retained page range, count, and timing details are hidden until the group is expanded again

#### Scenario: Navigate to next page
- **WHEN** a group has a next cursor and the user clicks next
- **THEN** only that group's page resource requests the cursor continuation while its bounded-or-exact count and other groups remain stable

#### Scenario: Update independent pagination timing
- **WHEN** a new resource page is pending or replaces the prior page
- **THEN** only the range and page timing fragment updates while the count and its timing/cache provenance retain the same Solid DOM fragment without flashing

#### Scenario: Recover from invalid cursor
- **WHEN** a page request returns the API's `invalid-cursor` conflict
- **THEN** the group clears its cursor stack, requests page one once, and presents an actionable error if recovery also fails

### Requirement: Configurable page size
The header SHALL contain the Electric-style page-size dropdown with values `10`, `20`, `50`, `100`, `250`, `500`, and `1000`, defaulting to `20`.

#### Scenario: Change page size
- **WHEN** the user selects a different supported page size
- **THEN** all authorization cursor stacks reset before visible paged queries refetch with the new size, while subject, permission, selected resource, theme, and cache mode remain stable

### Requirement: Lazy nested relationship exploration
Expandable resource nodes SHALL request bounded relationship pages only after expansion, filter or check authorization through `/api/eacl/*` as required by the schema path, prevent cycles, and retain independent cursor state per parent/type section.

#### Scenario: Expand nested relationships
- **WHEN** the user expands a resource with schema-defined child paths
- **THEN** only that resource's visible child sections request their bounded relationship and authorization data, render keyed child nodes, and use the same borderless caret treatment as other disclosure controls

#### Scenario: Encounter relationship cycle
- **WHEN** a relationship path revisits a resource already present in the current ancestry
- **THEN** the client does not recursively expand the repeated node and the UI remains responsive

### Requirement: Selected-resource detail
The detail panel SHALL identify the selected resource from its passthrough type/id, derive any readable label locally, show schema-valid permissions, and retrieve bounded subject lookup results for each visible permission through `/api/eacl/lookup-subjects`.

#### Scenario: Select a resource
- **WHEN** the user clicks a resource tree item
- **THEN** the detail panel identifies that resource and reactively loads permission-holder groups with timing and cache provenance

#### Scenario: Select a permission holder
- **WHEN** the user clicks a subject in resource detail
- **THEN** that subject becomes active and subject-dependent resource queries update through HTTP

### Requirement: Editable schema and graph
The schema segment SHALL show the committed Spice schema in an editable draft, presets, resource/relation/permission counts, dirty and writing states, validation errors, a derived graph, and a **Write Schema** action.

#### Scenario: Write valid edited schema
- **WHEN** the draft differs from the committed source and the user clicks **Write Schema** with valid Spice
- **THEN** the action is disabled while pending, the returned committed source becomes the draft baseline, schema metadata and graph refresh, invalid authorization cursors clear, and visible dependent queries refetch

#### Scenario: Write invalid edited schema
- **WHEN** the user clicks **Write Schema** with invalid Spice
- **THEN** the editor retains the draft, displays the safe validation error, preserves the prior committed graph and active authorization results, and permits correction and retry

#### Scenario: Expand schema graph
- **WHEN** the user expands the graph section
- **THEN** the graph implementation loads lazily, renders current nodes and relation paths, and disposes timers/listeners when the component is removed

### Requirement: Manually refreshed pretty cache snapshot
The cache segment SHALL include cache enablement, **Evict Cache**, and **Refresh cache** controls and SHALL render a deterministic two-space-indented snapshot in `<pre><code>`. The displayed snapshot MUST change only as the result of a user-initiated **Refresh cache** request.

#### Scenario: Open cache segment
- **WHEN** the user expands the cache segment without clicking **Refresh cache**
- **THEN** the client performs no cache snapshot request and displays the existing snapshot or an explicit not-yet-refreshed state

#### Scenario: Refresh cache snapshot
- **WHEN** the user clicks **Refresh cache**
- **THEN** the client requests `GET /api/cache`, captures the current cache mode and capture time, and replaces the display with pretty-printed returned statistics after success

#### Scenario: Authorization queries run after a snapshot
- **WHEN** one or more EACL queries complete after a cache snapshot was captured
- **THEN** timings and query results may update but the displayed cache snapshot remains byte-for-byte unchanged

#### Scenario: Evict after a snapshot
- **WHEN** the user clicks **Evict Cache** after capturing a snapshot
- **THEN** eviction completes and authorization resources invalidate, but the displayed snapshot remains unchanged until the user clicks **Refresh cache**

#### Scenario: Cache refresh fails
- **WHEN** a user-initiated refresh request fails
- **THEN** the cache segment reports the refresh error while retaining the last successful snapshot for inspection

### Requirement: Focused asynchronous states and recovery
Each independently fetched branch SHALL expose accessible pending, refreshing, empty, and error feedback without replacing the entire explorer or discarding its last successful unrelated data.

#### Scenario: One group request fails
- **WHEN** one resource group returns an API error while other groups succeed
- **THEN** that group displays a retryable error and the successful groups, subject controls, schema draft, and selected resource remain usable

#### Scenario: Refetch a populated branch
- **WHEN** an existing branch refetches because an input changed
- **THEN** the UI distinguishes focused refresh from first load and avoids a whole-page loading flash

### Requirement: Seed controls and progress
The header SHALL provide a validated positive server-count input and **Seed DB** action, SHALL keep the explorer mounted while seeding, and SHALL poll bounded progress/revision state so visible authorization queries can continue and react to each committed Datomic basis.

#### Scenario: Seed benchmark-shaped data
- **WHEN** the user submits a valid server count
- **THEN** duplicate submission is disabled, a compact progress banner is visible without replacing the panels, intermediate revision changes refetch affected visible resources, and completion updates totals while retaining unrelated UI state

### Requirement: Accessible and persistent local UI preferences
Interactive disclosures, switches, selectors, buttons, errors, and progress SHALL be keyboard operable and correctly labelled. Theme, expansion preferences, subject, permission, cache mode, and page size SHALL be restored from origin-local storage when valid without treating storage failure as fatal.

#### Scenario: Restore valid preferences
- **WHEN** the app reloads with valid stored preferences
- **THEN** controls initialize from them before dependent queries are keyed and invalid schema-derived selections are normalized after bootstrap

#### Scenario: Browser storage is unavailable
- **WHEN** local storage access throws or stored data is malformed
- **THEN** the explorer uses documented defaults and remains fully operable

### Requirement: Bounded client work and measurable responsiveness
The client SHALL render only fetched pages and expanded branches, SHALL lazy-load graph code, SHALL preserve stable list identities, and SHALL include an end-to-end benchmark that records request count, payload size, and interaction timing for the 10,000-server fixture.

#### Scenario: Navigate a large demo dataset
- **WHEN** the user changes subject, permission, page, or selected resource against the benchmark fixture
- **THEN** the client performs only requests required by visible dependent branches, does not download the complete dataset, and the benchmark report identifies any interaction exceeding the documented local target
