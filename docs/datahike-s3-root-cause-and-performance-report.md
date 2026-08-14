# EACL v8 + Datahike + S3: storage, memory, and performance investigation

**Date:** 2026-08-13  
**Audience:** EACL maintainers and Christian Weilbach / Datahike maintainers  
**Deployment:** EACL 8.0.0-SNAPSHOT, Datahike 0.8.1759, one million
permissioned server resources, S3 in `us-east-1`  
**Status:** replacement-store rebuild and production offline GC in progress

## Executive conclusions

1. **The 14.60 GB bucket is not the live database.** A read-only production
   reachability/inventory join found 95,572 physical live objects occupying
   900,944,377 bytes. The bucket had 1,083,519 current objects occupying
   14,603,085,252 bytes. Therefore 987,947 current objects and
   13,702,140,875 bytes are unreachable immutable index history. That is
   **91.18% of objects and 93.83% of current bytes**. The current bucket is
   16.21 times the byte size of its live state.

2. **S3 versioning is not the explanation.** The earlier complete inventory
   found only 257,945,173 noncurrent bytes. The large excess consists of
   current, uniquely keyed immutable objects that S3 lifecycle rules cannot
   identify as Datahike garbage.

3. **Diff buffering, fused roots, and commit-graph opt-out are active.** The
   live production connection reports `:diff-buf-size 256`,
   `:fuse-index-roots? true`, `:commit-graph? false`, and
   `:keep-history? false`. In isolated imports the combined features reduced
   PUTs by about 20–30%, but they reduce new write amplification; they do not
   reclaim objects made unreachable by later roots.

4. **The EACL Datahike adapter is using Datahike correctly.** It stores forward
   and reverse relationship tuples and uses bounded native EAVT/AVET
   `seek-datoms` scans. Datahike's Datalog search cache is not on this path, so
   `:search-cache-size 0` is correct.

5. **The principal cold-read problem is EACL planning, magnified by S3
   latency.** The current permission-directed algorithm merges globally
   ordered streams. To prove the first result, it must realize the head of
   every account/team/VPC-derived stream. The one-million-resource super-user
   page opens 4,536 relationship scans and caused approximately 3,935 unique
   Datahike node-cache misses. It took 148.4 seconds cold. With those nodes in
   RAM, the same cache-disabled EACL request took 214 ms, and a completed-answer
   hit took only a few milliseconds.

6. **High RSS is partly delayed reclamation, but not simply “GC has not run.”**
   Before an explicit full GC the steady production JVM used 1.354 GB of heap;
   afterward it used 591 MB. Heap commitment remained 1.648 GB and RSS stayed
   near 1.90 GB. G1 retains committed/touched regions and Linux RSS does not
   immediately fall when objects die. During import there is also real live
   pressure from expanded relationship transactions, four in-flight jobs,
   pending persistent-tree nodes, the node cache, and accumulated freed-node
   tracking.

7. **Use separate cache profiles.** For a future bulk import use a Datahike
   store cache of about 1,000 entries. For this public demo, return to the
   previously approved 8,192-entry serving cache after exact GC and validate
   the resulting heap/RSS envelope with a representative concurrent subject
   mix. Do not enlarge EACL's result caches; their measured occupancy is below
   1 MB.

8. **The laboratory throughput winner did not fit the production heap.** A
   2,000-item/four-in-flight profile cut PUTs and bytes at 10,000 resources,
   but repeatedly exhausted the production 5 GiB heap while writing the
   one-million-resource S3 store. The corrected production profile is 1,000
   items, two in flight, no delay, cache 1,000, and pressure-triggered GC only
   after completed transaction windows. The final 72,310 resources completed
   on one JVM with this profile.

9. **Keep the replacement bucket unversioned and online GC disabled.** Datahike
   supplies database time travel, while bucket versioning would retain objects
   removed by an offline sweep. The private replacement import itself reached
   746,509 current objects / 8,254,861,778 bytes before exact GC, proving that
   immutable index churn—not versions—dominates even in an unversioned bucket.

10. **Do not run production `gc-storage` casually.** It is destructive and
    must run in the writer JVM. The approved replacement-store sweep started
    with 746,509 current objects and must read their Konserve metadata
    serially before deleting unreachable objects. Deletes are free under
    current S3 pricing, but the exact sweep can take hours. The replacement
    bucket is unversioned, so successfully swept bytes are reclaimed rather
    than retained as noncurrent versions.

11. **The current S3 sweep has two backend-specific performance gaps.**
    Konserve's generic `list-keys` reads every `.ksv` object sequentially to
    deserialize its logical key and `last-write` timestamp, even though the S3
    client already offloads blocking calls to virtual threads. Afterward the
    generic fallback submits individual DELETEs because the S3 backing does not
    implement Konserve's combined multi-read/multi-write capability, despite
    having an internal 1,000-key S3 `DeleteObjects` helper for whole-store
    deletion. A bounded-parallel metadata enumerator plus a GC-specific bulk
    delete capability would preserve exact mark/safe-point semantics while
    materially reducing maintenance duration and request overhead. This demo
    does not replace the generic collector with an application-specific
    destructive implementation.

## Scope and evidence

This investigation started from the existing reports:

- [Seed pacing and read sizing](read-sizing.md)
- [Datahike S3 storage maintenance](storage-maintenance.md)
- [Datahike on T4g memory report](datahike-t4g-memory-report.md)
- [Versioned MinIO measurement](minio-measurement.md)
- [Datomic-to-Datahike port baseline](port-baseline.md)

It then read the exact resolved source and ran new controlled experiments.

### Resolved versions

| Component | Version / revision |
| --- | --- |
| EACL Datahike artifact | `dev.eacl/eacl-datahike 8.0.0-SNAPSHOT` |
| EACL source corresponding to artifact | `142882c56e2e4f0c4e37a5740fd0f0db96d066e9` |
| Demo source before this report | `730252d998ddcb06f254529b56e3fdd8b5f8771f` |
| Datahike | `0.8.1759` / `779724b60d1e4292a39868b2e27bfff8bf7e0e69` |
| persistent-sorted-set | `0.4.137` |
| Konserve | `0.9.369` (wins over Datahike's transitive `0.9.363`) |
| Konserve S3 | `0.1.37` |

As of this investigation, Datahike `0.8.1775` is the newest published tag.
Between `0.8.1759` and `0.8.1775`, the relevant writer, commit, GC, online-GC,
migration, and write-amplification files are unchanged except for a one-line
persistent-index fix associated with same-transaction history churn. There is
not yet a new comprehensive physical import/export path in those tags; the
existing `datahike.migrate` implementation still retransacts exported datoms
in batches.

### Test environment

- A **fresh** nREPL was started on port 7892 with `-Xms256m -Xmx4g`.
- A disposable MinIO server used
  `minio/minio:RELEASE.2025-09-07T16-13-09Z`.
- Toxiproxy 2.12.0 injected 2 ms in each direction. The observed MinIO GET p50
  was about 8–9 ms after protocol and local-container overhead.
- Every case used an isolated Datahike store UUID.
- The main test bucket had versioning enabled. A separate local MinIO bucket
  tested the proposed unversioned-build workflow.
- S3 GET/PUT/DELETE calls were counted at the Konserve S3 boundary.
- The initial production diagnosis was read-only: configuration, cache/memory statistics,
  S3 inventory, and a reachability mark with no sweep. One explicit JVM full
  GC was used to distinguish live heap from RSS. No temporary AWS bucket was
  needed, and no production Datahike or S3 object was written or deleted.
- Production has no paid S3 request-metrics filter; CloudWatch returned no
  `AllRequests` metric. Historical production request totals therefore come
  from object-version deltas, while exact per-operation behavior comes from
  source and MinIO instrumentation.

After the investigation the public health endpoint still reported `ready` on
the S3-backed revision.

## Datahike and Konserve source findings

### Datahike really does auto-batch parallel transactions

The self writer has separate transaction and commit loops. The commit loop
greedily drains every immediately pending commit-queue entry with `poll!`,
persists only the latest `:db-after`, then resolves every logical transaction
callback with that committed database. See
[`writer.cljc` lines 117–169](https://github.com/replikativ/datahike/blob/0.8.1759/src/datahike/writer.cljc#L117-L169).

Consequences:

- Sequentially submitting and awaiting every transaction prevents batching.
- The demo's four futures per window are the right basic shape.
- Batching is opportunistic. The exact grouping depends on how quickly the
  transaction loop produces work relative to the commit loop, so repeated
  runs can vary in PUT count even with the same logical chunks.
- Larger transaction chunks and parallel submission reduce durable commits,
  but larger applied DB values and expanded relationship transactions increase
  transient heap. There is a real throughput/memory tradeoff.

After the first durable commit, the writer threads the last commit id and skips
the ordinary per-commit branch-head read. This is why the instrumented imports
issued essentially no GETs unless online GC was enabled.

### One changed tree node is one S3 PUT

Persistent-sorted-set stores every newly written node in a pending-write map
and immediately admits it to the entry-counted LRU cache. Datahike drains those
pending nodes at commit. On the asynchronous path it starts every Konserve
`assoc` and then waits for all of them; see
[`writing.cljc` lines 379–390](https://github.com/replikativ/datahike/blob/0.8.1759/src/datahike/writing.cljc#L379-L390).

Konserve S3 does not provide an atomic/multi-key Datahike commit here. Each
immutable node is serialized as its own object and sent with one PUT. Blocking
AWS SDK calls run on virtual threads, so many PUTs overlap, but their byte
arrays, pending operations, and callbacks are simultaneously live. See the
[commit ordering and pending-write path](https://github.com/replikativ/datahike/blob/0.8.1759/src/datahike/writing.cljc#L392-L525)
and the [Konserve S3 virtual-thread bridge](https://github.com/replikativ/konserve-s3/blob/0.1.37/src/konserve_s3/core.clj#L367-L438).

The write-reduction options work as follows:

- Diff buffering avoids rewriting some child/spine nodes.
- Root fusion moves index roots into the mutable branch record, removing
  separate root PUTs but making each overwritten branch version larger.
- `:commit-graph? false` removes one immutable provenance object per durable
  commit.
- None of the three removes old immutable nodes after a later root no longer
  references them.

Datahike's own [write-amplification documentation](https://github.com/replikativ/datahike/blob/0.8.1759/doc/write-amplification.md)
states the same boundary: storage still grows with superseded nodes and must
be reclaimed with GC.

### One Datahike node-cache miss is one S3 GET

`CachedStorage.restore` first probes the in-process LRU. Only a miss calls
Konserve `get` and increments `:reads`; see
[`persistent_set.cljc` lines 422–526](https://github.com/replikativ/datahike/blob/0.8.1759/src/datahike/index/persistent_set.cljc#L422-L526).

Konserve S3 implements `PReadMissSafe`. It goes directly to GET, reads the
complete object once, and serves header, metadata, and value from that byte
array. It does **not** do HEAD followed by GET. See
[`core.clj` lines 235–321](https://github.com/replikativ/konserve-s3/blob/0.1.37/src/konserve_s3/core.clj#L235-L321)
and the [miss-safe declaration](https://github.com/replikativ/konserve-s3/blob/0.1.37/src/konserve_s3/core.clj#L619-L623).

Thus Datahike's `:reads` counter is a reliable node-object GET count for index
cache misses. There are a few additional direct Konserve operations during
connect, schema work, or GC, so whole-process S3 GETs can be slightly higher.

### The Datahike store cache is entry-counted, not byte-counted

`CachedStorage` uses `clojure.core.cache/LRUCache` with
`:store-cache-size` as its entry threshold. Leaf and branch objects are not all
the same size. A setting of 8,192 therefore means “at most 8,192 nodes,” not a
specific number of MiB.

When a tree node is replaced, persistent-sorted-set also records its address in
both `freed-addresses` and `freed-set`. With online GC disabled, those
connection-local collections grow during a long write process. They disappear
on process restart. They are not large enough to explain several GiB alone,
but they are one concrete cumulative importer retention source.

### Search cache is irrelevant to EACL's native scans

Datahike's search cache wraps the Datalog/search path. EACL calls
`seek-datoms` directly, so raising `:search-cache-size` cannot cache these
relationship traversals. It only duplicates bookkeeping for other queries.
Keeping it at zero is correct for this demo.

### Offline and online GC solve different problems

`d/gc-storage` performs an exact mark from every retained branch and then asks
Konserve to sweep every key not in the mark. Its safe point protects a commit
whose nodes have been written but whose branch head has not yet been flipped.
That safety depends on GC running in the **same JVM as all writers**; see
[`gc.cljc` lines 179–257](https://github.com/replikativ/datahike/blob/0.8.1759/src/datahike/gc.cljc#L179-L257).

Konserve's sweep reads every object's metadata to compare `:last-write` with
the safe cutoff, then deletes unreachable objects in batches; see
[`konserve/gc.cljc`](https://github.com/replikativ/konserve/blob/0.9.369/src/konserve/gc.cljc#L8-L41).
For Konserve S3 0.1.37, deletion falls back to individual object DELETEs,
although up to 1,000 are launched together.

Experimental online GC consumes the freed-node tracking during commits. In
non-crypto, single-branch mode it normally puts freed addresses on an
in-memory freelist rather than deleting them. Later writes reuse and overwrite
those S3 keys. See
[`online_gc.cljc` lines 139–215](https://github.com/replikativ/datahike/blob/0.8.1759/src/datahike/online_gc.cljc#L139-L215).

Important consequences:

- Online GC is only safe for one branch.
- A zero grace period is only safe when there are no long-lived readers.
- It bounds current key growth during import, but it is not an exact
  reachability collector; a final offline GC still finds garbage.
- In a versioned bucket, address reuse creates noncurrent versions. It can make
  the **current** view smaller without immediately reducing billed all-version
  bytes.
- In an unversioned, rebuildable build bucket, recycling really overwrites the
  old bytes and is substantially more useful.

## EACL source findings

### The Datahike relationship representation and scans are appropriate

Every relationship is stored as exactly two tuple datoms:

- a forward tuple on the subject entity;
- a reverse tuple on the resource entity.

The attributes are cardinality-many, indexed four-component tuples. See the
[Datahike schema](https://github.com/theronic/eacl/blob/142882c56e2e4f0c4e37a5740fd0f0db96d066e9/modules/eacl-datahike/src/eacl/datahike/schema.clj#L77-L122).

`subject->resources` and `resource->subjects` perform endpoint-local EAVT tuple
prefix seeks with a cursor tail. The adapter pads Datahike's vector lower bound
to full tuple arity and guards the endpoint/attribute/prefix so a missing range
cannot run into the next range. See
[`impl.clj` lines 66–132](https://github.com/theronic/eacl/blob/142882c56e2e4f0c4e37a5740fd0f0db96d066e9/modules/eacl-datahike/src/eacl/datahike/impl.clj#L66-L132)
and
[`db.clj` lines 104–156](https://github.com/theronic/eacl/blob/142882c56e2e4f0c4e37a5740fd0f0db96d066e9/modules/eacl-datahike/src/eacl/datahike/db.clj#L104-L156).

This is a good adjacency-list representation for permission-driven traversal.
Replacing these seeks with Datalog queries would add planning overhead and
would not solve the fan-out.

### Why the first globally ordered page opens thousands of scans

The acyclic engine produces one ordered result stream for each permission path
and, for arrows, one stream for each intermediate object. It then merge-sorts
and deduplicates those streams. See
[`v8.cljc` lines 3996–4037](https://github.com/theronic/eacl/blob/142882c56e2e4f0c4e37a5740fd0f0db96d066e9/modules/eacl/src/eacl/engine/v8.cljc#L3996-L4037)
and
[`v8.cljc` lines 4373–4524](https://github.com/theronic/eacl/blob/142882c56e2e4f0c4e37a5740fd0f0db96d066e9/modules/eacl/src/eacl/engine/v8.cljc#L4373-L4524).

The lazy merge first filters streams with `seq`, which realizes each stream's
head before the global minimum can be known. This is required for an exact
merge of arbitrary sorted streams; see
[`lazy_merge_sort.cljc` lines 107–120](https://github.com/theronic/eacl/blob/142882c56e2e4f0c4e37a5740fd0f0db96d066e9/modules/eacl/src/eacl/lazy_merge_sort.cljc#L107-L120).

The demo has 500 accounts at 2,000 servers each, four teams and two VPCs per
account. The super-user reaches all accounts and therefore all of those
intermediates. The server permission also includes several semantically
independent grant paths:

```text
admin + account->view + team->view + vpc->view + shared_admin
```

On this generated data many paths grant the same servers, but EACL cannot
discard them in general: the schema does not assert that a server's account,
team's account, and VPC's account must be identical. Static alias
canonicalization removes exact path duplicates, not data-dependent dominance.

This explains the measurements:

- 4,536 backend relationship scans;
- about 3,935 unique cold node GETs after shared tree paths are cached;
- 148.374 seconds cold in production;
- 214 ms with Datahike warm and EACL's completed-answer cache disabled;
- a few milliseconds for a completed-answer hit.

At 148.374 seconds / 3,935 misses, the critical path averages about 37.7 ms per
miss, which is consistent with same-region S3 plus serialization and traversal
overhead. Request price is negligible; serial latency is not.

### The general EACL optimization should be a cost-based alternate plan

The current permission-driven adjacency plan is excellent for sparse
permissions: it visits grants rather than scanning all resources. It is poor
for a dense subject such as this super-user because it opens thousands of
streams to return 20 items.

A general improvement should add a second plan:

1. scan resources of the requested type in global internal-EID order;
2. point-check `can?` for each candidate;
3. stop once the requested page and sentinel are filled.

This is excellent when selectivity is high and potentially terrible when a
subject can see only a few resources. EACL therefore needs relation/type
cardinality and selectivity estimates, then chooses between:

- **permission-driven:** current traversal/merge; and
- **resource-driven:** ordered candidate scan plus point checks.

The backend contract would need an ordered `objects-of-type`/candidate scan or
an EACL-maintained type index. The plan must preserve snapshot-bound cursor
identity, exact EID ordering, cooperative cancellation, reverse pagination,
and proof/cache scoping.

For this demo alone, simplifying the server permission would also reduce work,
but that changes what the demo demonstrates and is not a general engine fix.
A materialized broad-subject entitlement is another possible demo-specific
solution, but it adds write/storage invalidation complexity.

## Controlled experiment results

### Write-reduction features are active and useful, not sufficient

The following 2,000-server cases used 250-item chunks, four in-flight
submissions, the same schema/data profile, and injected object-store latency.
Opportunistic commit grouping makes individual runs vary, so the table should
be read directionally rather than as a microbenchmark ranking every
combination.

| Features | Seed PUTs | Current delta objects | Current delta bytes | Noncurrent bytes |
| --- | ---: | ---: | ---: | ---: |
| all three off | 834 | 814 | 6,989,439 | 27,772 |
| commit graph off only | 812 | 792 | 6,928,629 | 27,771 |
| root fusion only | 740 | 720 | 6,761,806 | 138,836 |
| diff buffer 256 only | 627 | 607 | 5,289,153 | 28,310 |
| diff + fusion | 564 | 544 | 5,329,388 | 440,515 |
| all three active, paired run | 581 | 561 | 5,120,082 | 451,365 |
| all three active, earlier repeat | 667 | 644 | 5,780,856 | 461,702 |

The paired all-active run used 30.3% fewer PUTs and 20.6% fewer total bytes
(current plus noncurrent) than all-off. Diff buffering made the largest
contribution. Root fusion traded separate current roots for a larger mutable
branch record, which is why noncurrent bytes increased.

Larger diff buffers were not a free win:

| Diff buffer | 2k PUTs | 2k current bytes | 2k noncurrent bytes | Cold page GETs |
| ---: | ---: | ---: | ---: | ---: |
| 64 | 635 | 5,647,863 | 226,453 | 14 |
| 256 | 581 in paired run | 5,120,082 | 451,365 | comparable |
| 1,024 | 480 | 4,144,468 | 1,311,038 | 14 |
| 4,096 | 389 | 3,590,814 | 4,006,752 | 14 |

At 10,000 servers with 2,000-item chunks and a 1,000-entry seed cache, 256 had
the smallest measured total bytes and heap sample. The 1,024 and 4,096 cases
shifted much more data into overwritten branch versions and used more heap.
The source also warns that cold full-range scans pay to project buffered
diffs. **Keep 256 for this workload.**

### Batching and seed-cache matrix

Small 2,000-server cases show Datahike's auto-batching effect:

| Transaction size | In flight | Seed time | PUTs |
| ---: | ---: | ---: | ---: |
| 250 | 1 | 1,382.8 ms | 746 |
| 250 | 4 | 1,044.9 ms | 667 |
| 1,000 | 4 | 803.9 ms | 531 |
| 2,000 | 4 | 647.1 ms | 504 |

The more representative 10,000-server cases included latency injection:

| Transaction size / cache | Seed time | PUTs | Current objects | Current bytes | Sampled used heap |
| --- | ---: | ---: | ---: | ---: | ---: |
| 250 / 8,192 | 4,601 ms | 3,345 | 3,249 | 31,698,491 | 1,166,666,216 |
| 2,000 / 8,192 | 3,441 ms | 2,259 | 2,223 | 20,555,307 | 1,139,660,904 |
| 2,000 / 1,000 | 3,242 ms | 2,258 | 2,222 | 20,527,693 | 763,840,568 |

Relative to 250 / 8,192, the 2,000 / 1,000 profile reduced:

- PUTs by 32.47%;
- current objects by 31.58%;
- current bytes by 35.15%;
- elapsed time by 25.21%;
- sampled used heap by about 376 MB.

The heap samples were taken in one long-lived laboratory JVM before a full GC,
so they are directional, not a clean per-configuration retained-heap
measurement. The object and request counts are exact.

The demo loader eagerly constructs about 2,000 server entities and about 6,000
server relationships per account. A relationship chunk is much larger than
its public item count suggests: EACL adds endpoint identity guards, schema
stamps, and forward/reverse tuples. A 2,000 relationship chunk is therefore a
large native Datahike transaction. Do not raise the cap above the demo's
existing 2,000 limit without another heap test.

### Cold and warm read behavior under injected latency

| 10k seed profile | Cold page | Cold GETs | GET total time | Warm page | Warm GETs |
| --- | ---: | ---: | ---: | ---: | ---: |
| 250 / cache 8,192 | 382.7 ms | 43 | 351.6 ms | 4.53 ms | 0 |
| 2,000 / cache 8,192 | 427.4 ms | 44 | not materially different | 2.84 ms | 0 |
| 2,000 / cache 1,000 | 378.1 ms | 43 | dominant share | 2.43 ms | 0 |

Seed chunking changed write amplification but did not change the logical live
database enough to alter cold page IO. At this scale, a 1,000-node cache held
the page's 43-node working set. The cold page was almost entirely object GET
latency; the warm page was local computation.

### Exact local GC measurements

For the optimized, versioned 10,000-server store:

| Measurement | Result |
| --- | ---: |
| Before GC | 2,252 objects / 20,737,106 bytes |
| GC S3 operations | 2,256 GETs / 1,287 DELETEs |
| GC wall time | 21.45 seconds |
| After GC | 965 objects / 8,600,452 bytes |
| Removed | 1,287 objects / 12,136,654 bytes |

GC removed 57.15% of current objects and 58.53% of current bytes. The near
one-to-one relationship between pre-GC objects and GETs confirms that the
current Konserve sweep reads every object's metadata.

At 2,000 servers, a separate exact GC left 197 objects / 1,744,176 bytes.
These live-set sizes are consistent with the production reachability result;
small-scale linear extrapolation from the 10,000 store gives about 96,500 live
objects, very close to production's 95,572.

### Unversioned build with online GC, then exact GC

The proposed fresh-demo workflow was tested in a separate unversioned MinIO
bucket with 10,000 new servers, transaction size 2,000, four in flight, cache
1,000, and online-GC grace zero.

| Stage | Objects | Bytes | Notes |
| --- | ---: | ---: | --- |
| after seed/reopen | 1,631 | 14,854,675 | online GC recycled freed addresses |
| after exact offline GC | 866 | 7,695,911 | 765 objects / 7,158,764 bytes removed |

The seed took 2.593 seconds and 2,239 PUTs. Online GC added 36 GETs. The exact
final GC took 15.20 seconds, with 1,635 GETs and 765 DELETEs. Because the
bucket was unversioned there were no retained noncurrent bytes.

After GC, a new connection reported the durable total of 10,048 servers and a
real super-user EACL page returned 20 items. Its cold page took 189.6 ms and 22
GETs under injected latency.

This validates the workflow at small scale; it does not prove that experimental
online GC is safe for arbitrary production use. The safety preconditions were
deliberately narrow: one branch, one writer JVM, no concurrent readers, and
rebuildable data.

### Recursive schema test

The existing production demo data is non-recursive, so the laboratory also
installed the demo's recursive schema and added 100 real server-parent edges.

When the recursive relation was empty, EACL correctly selected the bounded
acyclic route:

- 421.3 ms cold;
- 46 GETs;
- 93 backend scans;
- 20 results.

With the parent relation populated, EACL selected its generated recursive
fixed-point route:

- 183.4 ms cold;
- 15 GETs;
- 75 backend commands;
- 557 fetched stream datoms;
- 128 advanced datoms;
- 93 unique derived grants;
- 139 rule applications;
- maximum queue depth 85;
- 672 retained logical units;
- 20 returned results.

With EACL caching enabled, the first warm-store recursive request took 17.0 ms
and no S3 IO. It occupied 20,374 bytes of projection cache, 3,072 bytes of
answer cache, and 7,168 bytes of continuation cache. The exact repeat hit took
1.21 ms with no backend work.

This proves that the active recursive path works and stays bounded on this
fixture. It does **not** establish one-million-resource recursive performance.
The recursive engine's default limits are 100,000 derived grants, 100,000
advanced datoms, and 100,000 queued work items; these are logical limits, not
heap-byte limits. A one-million recursive rollout needs a separate scale test.

## Production findings

### Replacement import profile and exact result

The approved replacement store uses deterministic account ordinals and durable
per-account completion markers, so a restart resumes rather than appending a
different random fixture. The four small bootstrap accounts contain 48 servers;
224 generated accounts contribute the remaining 999,952 for an exact total of
1,000,000. Generated account sizes have this realized distribution:

| Band | Accounts |
| --- | ---: |
| 1–2,000 servers | 131 |
| 2,001–7,500 | 62 |
| 7,501–20,000 | 23 |
| 20,001–50,000 | 8 |

The generated mean is 4,464 servers/account, the minimum is 3 (the exact final
remainder), and the maximum is 43,186. Every account has at least one server;
team/VPC fan-out is capped by that account's server count, so every generated
owner, team leader, and VPC administrator has a non-empty authorization path.
`user-1` receives a 4,889-server generated account plus its 12-server bootstrap
account, avoiding the old default landing query over all resources. Only the
super-user has platform-wide access.

The first production attempts confirmed that the 2,000/four-in-flight
laboratory winner was unsafe at this scale: the 5 GiB heap exited at durable
totals 576,450 and 696,938. An account-boundary collection guard advanced to
880,292, but another four-by-2,000 window still exhausted the heap at 927,690
before its post-window guard could execute. The corrected 1,000/two-in-flight
profile completed the final 72,310 servers in the same JVM with no restart.
These failed attempts did not duplicate logical resources because names and
progress markers are deterministic and idempotent; they did contribute
unreachable immutable objects that the exact offline sweep must remove.

Before exact GC, the new unversioned bucket contained 746,509 current objects
and 8,254,861,778 bytes. That independent result proves version history was not
the source of the large physical footprint.

### Replacement-store request cost

CloudWatch's prefix-scoped S3 request metrics, queried at
`2026-08-13T21:59:44Z`, covered the replacement bucket from its creation through
the latest published minute (`2026-08-13T21:55:00Z` for GETs):

| Operation | Requests | Current rate | Cost |
| --- | ---: | ---: | ---: |
| PUT | 749,527 | $0.005 / 1,000 | $3.7476 |
| GET | 970,858 | $0.0004 / 1,000 | $0.3883 |
| HEAD | 45 | $0.0004 / 1,000 | $0.00002 |
| DELETE | 625,785 | free | $0.0000 |
| **Total request cost** |  |  | **$4.1360** |

This is a conservative maneuver total because the GET metric also includes
post-cutover verification and public reads through that timestamp, not only
the import and exact GC. CloudWatch recorded 8.337 GB uploaded and 10.563 GB
downloaded at the S3 boundary. The S3 store and EC2 writer are in the same AWS
region, so that traffic did not add an S3 regional data-transfer charge.

### Exact current/live/unreachable bytes

The new production operation did two read-only phases:

1. Datahike marked the keys reachable from the current branch without
   sweeping.
2. S3 listed current object keys and sizes; the results were joined by physical
   key.

| Production measurement | Objects | Bytes |
| --- | ---: | ---: |
| current S3 inventory | 1,083,519 | 14,603,085,252 |
| physically reachable live set | 95,572 | 900,944,377 |
| unreachable current set | 987,947 | 13,702,140,875 |
| live percentage | 8.8205% | 6.1695% |
| unreachable percentage | 91.1795% | 93.8305% |

The logical mark contained 95,575 keys and the physical candidate set 95,576;
95,572 intersected current S3 objects. The tiny difference comes from logical
root/head identities that are not separate objects under fused roots and
commit-graph opt-out. It does not affect the conclusion.

The average physical live object is 9,427 bytes. The earlier 10,000-server
post-GC average was 8,912 bytes. This independent agreement is strong evidence
that approximately 0.9 GB, not 14.6 GB, is the plausible live footprint for
this one-million-resource database.

### Production PUT and commit evidence

The pre-seed versioned bucket had 49 object versions. The post-seed inventory
had 1,093,050 versions, a delta of **1,093,001 recorded object versions** over
the seed/deployment window. Because the inventory was inside the seven-day
noncurrent retention window, this is a near-exact PUT count for the window,
not merely a count of current keys. It includes small schema, fixture, and
post-seed application writes in addition to the million-resource load.

The post-seed difference between all versions and current objects was about
9,539. Most of those are overwritten mutable branch-head versions, so this
also indicates roughly 9,500 durable commits. It is an estimate, not an exact
commit counter, because other mutable keys and setup writes also create
versions.

Using current `us-east-1` S3 Standard rates, 1.093 million PUTs cost about
$5.47. A cold 3,935-GET page costs about $0.0016. The economic problem is
modest; the performance and operational footprint are not. AWS documents that
request pricing is per operation and that DELETE requests are free on the
[S3 pricing page](https://aws.amazon.com/s3/pricing/).

### Estimated production GC request shape

Source behavior plus the exact local GC predicts approximately:

- 1,083,519 object metadata GETs;
- 987,947 individual DELETEs;
- about 1,084 paginated LIST requests;
- a small number of branch/schema GETs.

At current prices, GETs are about $0.43 and LISTs about $0.006; DELETEs are
free. Same-region data transfer is not the issue. Runtime and service impact
are the concerns: the small local sweep averaged roughly 8.7 ms per metadata
GET and took 21.45 seconds for 2,252 objects. Production S3 latency can make a
million-object sweep an hours-long maintenance operation.

With bucket versioning enabled, each DELETE creates a delete marker and the
old bytes become noncurrent. AWS describes that behavior in its
[version deletion documentation](https://docs.aws.amazon.com/AmazonS3/latest/userguide/DeletingObjectVersions.html).
The deployed lifecycle expires noncurrent versions after seven days, so
current-object reports should shrink immediately after a sweep, while billed
all-version bytes fall later.

### Production memory and RSS

After the reachability work had populated 4,464 Datahike cache entries:

| Memory observation | Before explicit full GC | After full GC + 5 s |
| --- | ---: | ---: |
| used Java heap | 1,354,124,776 bytes | 591,361,376 bytes |
| committed Java heap | 1,648,361,472 bytes | 1,648,361,472 bytes |
| maximum Java heap | 3,221,225,472 bytes | 3,221,225,472 bytes |
| process RSS | 1,900,716 KiB | 1,904,828 KiB |

A later sample remained in the same state: 619.7 MB used heap, 1.648 GB
committed heap, and 1,905,124 KiB RSS. The host still had about 5.67 GB
available on `t4g.large`.

This establishes:

- a large part of pre-GC heap was dead/reclaimable;
- G1 did not reduce committed heap after collection;
- RSS did not fall with live heap;
- the steady live heap after a deliberately broad reachability/cache workload
  was roughly 0.6 GB;
- the roughly 1.9 GB steady RSS is not 1.9 GB of live Clojure/Datahike objects.

It does not fully attribute native memory. The production runtime lacks
`jcmd`, so there is no same-process NMT category dump or class histogram.

The previous seed peaks remain real:

- about 3.2 GiB process peak on `t4g.medium` before the safety stop;
- about 5.4–5.6 GiB on the higher-throughput `t4g.large` seed;
- about 1.8–1.9 GiB for the clean read JVM.

The seed and read envelopes are different. A smaller permanent instance can be
valid even when a larger temporary importer is necessary.

### Cache occupancy and sizing

The current production Datahike node cache reports:

- configured maximum: 8,192 entries;
- current entries: 4,464;
- total node restores from S3 since process start: 4,464;
- PSS node accesses: 51,265;
- writes: 0;
- freed-address/freelist entries: 0 in the clean read JVM.

The canonical page alone previously established a 3,935-node working set.

The current EACL caches report:

| EACL tier | Current occupancy | Configured maximum |
| --- | ---: | ---: |
| answer | 12 entries / 18,944 bytes | 16 MiB |
| projection | 70 entries / 9,280 bytes | 4 MiB |
| denotation | 0 | 4 MiB |
| continuation | 9 entries / 683,456 bytes | 512 entries / 128 MiB |

There have been no continuation evictions or rejections. EACL cache growth is
not the current memory problem, and increasing these limits would not improve
cold traversal.

One hardening gap remains: the core continuation store has a 128 MiB default
weight ceiling, but the general client option currently passes only
`:max-entries`; the demo cannot independently lower continuation bytes. Before
targeting a 4 GiB host under varied public traffic, EACL should expose that
weight option and the demo should set approximately 32 MiB / 256 entries.
That is a defensive cap, not a fix for today's RSS.

## Recommendations

### 1. Original production store and approved replacement

Do not change write-amplification flags; the right ones are already active.

The operator subsequently approved a fresh unversioned replacement store and
one exact offline sweep, with the public service held in maintenance. The
accepted procedure is:

1. stop public writes and confirm exactly one writer JVM/one branch;
2. establish and verify a recovery mechanism;
3. retain the complete old store as the recovery point and record the new
   store's exact current-object inventory;
4. confirm the replacement database has exactly 1,000,000 servers and no seed
   is in flight;
5. run `d/gc-storage` through the existing writer JVM during a maintenance
   window;
6. wait for completion, restart, and verify server totals, EACL checks,
   pagination, recursive/non-recursive schema operations, and browser behavior;
7. record the unversioned current-object inventory immediately after GC;
8. monitor memory, S3 errors, and application health for the whole sweep.

The initial diagnosis performed no production GC. The later replacement-store
operation received separate approval and invokes GC only in its sole writer
JVM after an exact one-million-resource seed and pre-GC inventory.

### 2. Future one-million-resource demo rebuild

Recommended build profile:

```text
transaction size             1,000
in-flight submissions        2
inter-window delay           0
Datahike store cache         1,000 during seed
Datahike search cache        0
diff buffer                  256
fuse index roots             true
keep history                 false
commit graph                 false
```

Recommended lifecycle:

1. Create an isolated, initially unversioned S3 bucket/store in the target
   region. Do not publish it or allow readers.
2. Keep online GC disabled. After the import, run exact offline `gc-storage`
   through the sole writer JVM while public reads remain in maintenance.
3. Submit the existing bounded parallel windows. Continue per-account durable
   progress markers and external RSS/host-memory guards.
4. Keep periodic JVM restarts for a very large import if RSS crosses the
   approved guard; restarts clear committed heap, Datahike cache, and
   connection-local freed-node tracking.
5. Stop writes and run one exact offline GC in the same writer JVM.
6. Reconnect in a fresh JVM and verify counts, schema, forward/reverse EACL
   lookups, pagination, cancellation, and a real recursive fixture.
7. Keep the dedicated bucket unversioned; Datahike provides logical history.
8. Publish the application only after the compact store is accepted.

This avoids preserving every importer overwrite as an S3 version. If the
announced Datahike import/export release lands first, benchmark it against this
workflow; prefer it only after it demonstrates a canonical/reclaimed physical
result and passes the same reconnect/EACL tests.

### 3. Serving cache profile

Start read serving with the approved profile:

```text
Datahike store cache         8,192 entries
Datahike search cache        0
EACL answer cache            16 MiB (unchanged)
EACL projection cache        4 MiB (unchanged)
EACL denotation cache        4 MiB (unchanged)
EACL continuation cache      target 32 MiB / 256 entries after option exists
EACL request concurrency     4 (unchanged until load test)
```

Validate 8,192 with a representative mix of subjects, permissions, page
directions, counts, and recursive requests at concurrency four. Compare the
delta of Datahike `:reads` to `:accessed` after the warm-up pass, and retain the
profile only while the clean-JVM heap/RSS and host-memory gates pass.

### 4. EC2 sizing

Keep `t4g.large` while response-time testing continues, as already directed.
The clean read evidence still supports a later `t4g.medium` trial, but use a
read-specific JVM envelope rather than the current 3 GiB maximum without
testing. A reasonable first trial is `-Xms256m -Xmx2304m`, cache 6,144, search
cache zero, concurrency four, with these gates:

- at least 20% host-available-memory headroom under representative load;
- no swap or OOM kill;
- no sustained T4g credit exhaustion in `standard` mode;
- no cache-miss oscillation after warm-up;
- warm p95 within the existing 250 ms server-side objective;
- bounded/cancelled requests release their traversal work.

If the heap or host gate fails under real reads, retain `t4g.large`. Import
peaks alone are not a reason to reject medium; read-load failure is.

### 5. EACL performance work

Open a separate EACL change for a cost-based dense-subject plan. The work
should include:

- an ordered resource-candidate backend primitive;
- relation/type cardinality statistics;
- a deterministic plan choice between permission-driven and resource-driven
  enumeration;
- S3-latency benchmarks for sparse and dense subjects;
- stable cursor/basis and reverse-page tests;
- cooperative cancellation at every candidate/scan boundary;
- non-recursive and recursive workload coverage;
- proof that caches cannot cross source, schema, or snapshot identities.

Prewarming and completed-answer caching remain useful operational mitigations,
but they do not replace this planner work: every new database basis or cold JVM
must otherwise pay the thousands-of-stream fan-out again.

## What is known, inferred, and still open

### Established by exact measurement

- production live, unreachable, and current byte counts;
- the 93.83% unreachable-byte root cause;
- production flags and cache occupancy;
- one cache miss to one S3 object GET on the Datahike node path;
- MinIO PUT/GET/DELETE counts;
- batching, chunk-size, cache-size, GC, and recursive fixture results;
- post-full-GC live heap versus unchanged RSS;
- current Datahike 0.8.1775 has no relevant storage/import change over 0.8.1759.

### Strong inference

- roughly 9,500 durable production commits from overwritten-version count;
- an hours-scale production GC window from one-GET-per-object behavior;
- a post-GC production current footprint near 0.9 GB, because it was measured
  directly from the physical live-key intersection.

### Still requires a separate experiment/change

- one-million-resource recursive-schema performance;
- representative concurrent read behavior on `t4g.medium`;
- exact native-memory attribution with a full JDK/NMT tooling image;
- production GC wall time and final all-version behavior;
- the cost/selectivity model for a general EACL resource-driven plan;
- the announced future Datahike import/export release.

The central finding is no longer uncertain: the bucket is large because
unreachable immutable index nodes were never swept, and the cold page is slow
because EACL opens thousands of permission-derived streams whose Datahike
nodes are fetched from S3. The adapter and active Datahike flags are sound;
the remaining work is lifecycle management, separate import/read cache
profiles, and an EACL planner that recognizes dense authorization queries.
