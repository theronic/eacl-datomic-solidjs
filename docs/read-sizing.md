# Seed pacing and read-sizing evidence

The permanent EC2 size is selected for the steady read workload, not for the
largest transient heap observed while bulk data is being created. Production
seeding defaults to transactions of 250 items, no artificial pause, and four
in-flight submissions. The bounded window lets Datahike auto-batch pending
commits while capping loader concurrency. The values are validated and can be
adjusted with `EACL_DATAHIKE_DEMO_SEED_TRANSACTION_SIZE`,
`EACL_DATAHIKE_DEMO_SEED_PAUSE_MS`, and
`EACL_DATAHIKE_DEMO_SEED_IN_FLIGHT`.

For the operator-approved temporary `t4g.large` loader, the transaction size is
500 and the inter-transaction delay is zero. The external loader restarts and
full-GCs the JVM after each 80,000-server job while enforcing maximum RSS and
minimum host-available-memory stop conditions.

The acceptance sequence is deliberately split:

1. accept the 48-resource fixture and S3 persistence;
2. seed in paced, committed batches while reads and progress remain available;
3. stop the seed JVM after at least 1,000,000 servers are committed;
4. remove the seed-only override and start a clean `t4g.large` JVM with
   `-Xms256m -Xmx3g` and summary-level Native Memory Tracking;
5. leave that public service available for operator response-time testing;
6. only after operator confirmation, return to `t4g.medium`, restart cleanly,
   and measure reads after full GC;
7. accept the permanent size only with at least 20% memory headroom, no
   sustained CPU-credit exhaustion or unhandled 5xx responses, and warmed
   default-page p95 no greater than 250 ms.

If paced loading cannot finish, it does not automatically justify a larger
permanent instance. A temporary same-instance resize is a separate cost change
that requires operator approval. Failure of the clean read gates does justify
returning to the permanent-size approval decision.

## Local durable-store preflight

On 2026-08-12, a file-backed process seeded 100,000 additional servers with
the production defaults (250 items and 50 ms). It committed 100,048 total
servers in 456.439 seconds. At completion it used 287,138,864 heap bytes and
836,141,056 resident bytes; the durable test directory occupied 1.2 GB.

That process exited. A separate JVM with `-Xms512m -Xmx3g` reconnected to the
same store and ran 100 warmed default-page HTTP-boundary reads:

| Measurement | Result |
| --- | ---: |
| Reconnected servers | 100,048 |
| p50 | 2.130 ms |
| p95 | 3.594 ms |
| maximum | 16.975 ms |
| average response | 2,934.82 bytes |
| post-full-GC heap used | 125,468,376 bytes |
| post-full-GC RSS | 773,586,944 bytes |
| maximum heap | 3,221,225,472 bytes |

The recorded preflight used a 512 MiB initial heap; production lowers that
initial commitment to 256 MiB while retaining the same 3 GiB maximum. The
local preflight passes the latency and heap-ratio gates at 100,000
resources. It supports, but cannot prove, the `t4g.medium` hypothesis: the
machine architecture, local file latency, CPU count, and dataset scale differ
from the target EC2/S3 system. The decisive evidence is the clean-JVM
one-million-resource production measurement above.

A separate one-million-resource in-memory diagnostic exhausted a 3 GiB heap.
That result correctly rejects the in-memory backend as production sizing
evidence; it does not show that a clean durable-store read JVM needs the same
seed-time footprint.

## Production pre-seed baseline

Captured on 2026-08-12 immediately after public HTTPS acceptance and before
starting the million-resource job:

| Measurement | Result |
| --- | ---: |
| Fixture servers | 48 |
| S3 current objects | 29 |
| S3 current bytes | 180,057 |
| S3 total versions | 49 |
| S3 total version bytes | 439,017 |
| S3 noncurrent bytes | 258,960 |
| JVM heap used / committed / maximum | 490,580,736 / 811,597,824 / 3,221,225,472 bytes |
| JVM RSS / high-water RSS | 916,992 / 916,992 KiB |
| Host available memory | 2,729,046,016 bytes |
| CPU utilization, latest 5-minute average | 0.85% |
| CPU credit balance | 10.92 credits |

Both the application and Caddy were active, enabled, and healthy. No paid S3
request-metrics filter is configured; the version/object counts above are the
production write-amplification baseline, and request cost remains based on the
measured MinIO request rate recorded in the approved cost model.

## Production million-resource acceptance

The bounded loader finished at exactly 1,000,048 permissioned server
resources. A fresh normal-profile JVM then reopened the S3 store with
`-Xms256m -Xmx3g`; Jetty became ready after 3 minutes 45 seconds. After the
automatic cache prewarm completed, Java RSS was 1,832,164 KiB and the host
reported 5,855,404,032 bytes available. There was no swap, seed-only systemd
drop-in, or cache capacity pressure.

The original fresh-process prewarm performed the canonical super-user
20-server page and a demand-bounded 50,000-server count. It completed in
216,018 ms. This proved the cold delay was backend traversal and S3/index
access, not delayed GC or cache eviction. The deployment now prewarms only the
page; counts are requested after a page settles and never block readiness.
Once populated, the browser reported a 5.5 ms page hit and an 8.7 ms count hit.
Repeated direct API hits spent about 3 ms inside the server; a reused HTTPS
connection observed 242–250 ms end to end from the operator's South Africa
connection. A later page-only cold prewarm still exceeded three minutes and
was cooperatively cancelled while the store cache was being measured. A
clean, instrumented run completed in 148,374 ms. The page requires 4,536
indexed relationship scans: EACL must open the account, team, VPC, and
direct-grant streams before it can prove the first 20 globally ordered
results. After those Datahike nodes were resident, the same query with EACL's
completed-answer cache disabled took 214 ms; the completed-answer hit remained
a few milliseconds.

Production therefore starts a bounded page-only prewarm after Jetty and nREPL
are ready. Health and bootstrap do not wait for it, its state is visible from
`GET /api/cache`, and its cancellation token is signalled during shutdown.
The Datahike store cache is 8,192 entries, enough to retain the observed
3,935-node canonical working set plus subsequent explorer traffic. The
per-snapshot search cache is disabled: these EACL scans use distinct native
index seeks, so memoizing identical search calls did not address the cold
fan-out and only duplicated cache bookkeeping.

The retained cache footprint after prewarm and acceptance traffic was small:

| Tier | Used | Limit | Evictions/rejections |
| --- | ---: | ---: | ---: |
| Completed answers | 8,320 bytes / 6 entries | 16 MiB | 0 |
| Projections | 2,756 bytes / 21 entries | 4 MiB | 0 |
| Denotations | 0 bytes | 4 MiB | 0 |
| Continuations | 337,472 bytes / 1 entry | 512 entries / 128 MiB | 0 |

The provider recorded 27 subproblem hits, 29 misses, and 20 avoided backend
operations with no oversized publication, proof, or invalid-result failure.
These figures reject a larger cache allocation: the existing bounds are far
from capacity, while a cold computation must finish before any completed
answer can be published. Production now prewarms the canonical expensive path
after readiness and exposes its live state through `GET /api/cache`.

The versioned S3 bucket contained 1,083,511 current objects totaling
14,602,949,290 bytes (13.60 GiB). Including noncurrent versions it contained
1,093,050 versions totaling 14,860,894,463 bytes (13.84 GiB); the 257,945,173
bytes of noncurrent data are subject to the seven-day expiration policy.

A read-only reachability walk subsequently found 95,575 reachable store keys.
Compared with the 1,083,511 current S3 objects, approximately 987,936 objects,
or 91.2% by count, were unreachable immutable storage history. Noncurrent S3
versions accounted for only 257,945,173 bytes, so shortening the version
lifecycle cannot explain or materially fix the 14.60 GB current footprint.

The two-process helper functions are `benchmark/seed-file!` and
`benchmark/read-file!` in `server/dev/benchmark.clj`. The caller supplies an
explicit temporary store path and UUID and owns cleanup of that test data.
