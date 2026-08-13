# Datahike S3 storage maintenance

## Confirmed production diagnosis

The one-million-resource store contained 1,083,511 current S3 objects totaling
14,602,949,290 bytes. All versions totaled 14,860,894,463 bytes, so noncurrent
versions contributed only 257,945,173 bytes.

A read-only Datahike reachability walk completed in 108.7 seconds and found
95,575 reachable keys. The difference is approximately 987,936 current objects,
or 91.2% of current objects by count. They are unreachable immutable index
nodes left behind as commits publish new persistent-tree roots. They are not
live EACL resources, and they are not primarily S3 version history.

This is an exact object-count comparison, not a byte-summed live-set inventory.
Object sizes vary, so 91.2% unreachable by count must not be presented as 91.2%
of bytes. The byte-level result we can state exactly is that current objects
occupy 14,602,949,290 bytes while noncurrent versions add only 257,945,173
bytes; S3 version retention therefore cannot explain the current footprint.

Diff buffering, fused roots, `keep-history? false`, and
`commit-graph? false` reduce the number of writes. They do not sweep objects
made unreachable by later roots. S3 lifecycle expiry removes noncurrent object
versions; it does not discover unreachable current keys.

## Reduce growth during future imports

The loader submits chunks through a bounded in-flight window. Datahike's writer
preserves transaction order while its commit loop drains pending transactions
and persists the newest database state. Keeping four submissions in flight
therefore lets Datahike auto-batch several logical transactions into fewer S3
commits without constructing an unbounded import in memory.

The required barriers are:

1. submit one bounded window of entity chunks and wait for every result;
2. only then submit relationship chunks, because their endpoints must exist;
3. record the account's durable server contribution only after both stages
   complete;
4. advance public progress only after that durable marker commits.

Measure object count, current bytes, all-version bytes, PUTs, duration, heap,
and RSS at several scales. A small-seed byte-per-resource extrapolation is not
reliable because immutable-tree rewrite history grows with commit count and
tree shape.

Datahike 0.8.1759 also contains experimental online GC. Do not enable it on the
production store merely by changing connection configuration: it is a stored
behavior choice, relies on single-branch/freed-address assumptions, and needs a
disposable MinIO stress test with long-lived readers and restart verification
first. Reassess it with the Datahike release that lands the announced
import/export work.

The deployed production connection was inspected directly and confirms that
the intended write-reduction settings are active:

- `:index-config {:diff-buf-size 256}`
- `:fuse-index-roots? true`
- `:keep-history? false`
- `:commit-graph? false`

The 91.2% unreachable-object result is therefore evidence that these settings
reduce write amplification but do not replace garbage collection.

## Reclaim an existing store

`datahike.api/gc-storage` performs a full mark and destructive sweep. Its safe
point protects objects written by in-flight commits only when GC runs in the
same JVM as every writer. Never run it from a sidecar, a second application
process, or a remote script that opens another self-writer connection.

Before any production sweep:

1. confirm there is exactly one application/writer JVM and one database branch;
2. capture current object/version counts and application health;
3. create and verify an approved recovery point using supported export/import
   when available, or retain a separately approved S3 copy/version recovery
   plan;
4. repeat a read-only reachability report and reconcile its live-key count;
5. run the same Datahike version and configuration against a disposable MinIO
   copy, invoke `gc-storage`, reconnect, and run permission/pagination tests;
6. present the predicted delete scope, request cost, recovery method, and
   maintenance window for explicit operator approval;
7. only then invoke `(datahike.api/gc-storage (:conn @system/!system))` through
   the writer JVM's loopback-only nREPL;
8. wait for completion, restart cleanly, and rerun database, EACL, and browser
   acceptance before treating the maintenance as successful.

With S3 versioning enabled, deletes create delete markers and old bytes become
noncurrent. Current-object inventory should fall after the sweep, while billed
all-version bytes decline only as the noncurrent-version lifecycle expires.
Do not empty the bucket or shorten recovery retention as a substitute for a
verified Datahike mark-and-sweep.
