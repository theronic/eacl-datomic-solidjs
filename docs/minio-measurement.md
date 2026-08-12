# Versioned S3 compatibility measurement

Measured on 2026-08-12 with the opt-in test
`eacl-datahike-demo.storage-test` against disposable
`minio/minio:RELEASE.2025-09-07T16-13-09Z` storage. Bucket versioning was
enabled before database creation. The test used the pinned Datahike 0.8.1759,
Konserve 0.9.369, and Konserve S3 0.1.37 dependency graph.

The probe created a new S3 store, installed EACL and demo schema, transacted 48
fixture servers, checked a real permission, added one logical 2,000-server seed
batch using 250-item transactions and 50 ms pacing, released the connection,
reconnected by the same UUID, checked persisted
totals and authorization, and deleted only the disposable store. It passed 7
assertions with no failures.

| Measurement | Fixture | 2,000-server delta |
| --- | ---: | ---: |
| Current objects | 29 | 714 |
| Current bytes | 180,376 | 6,638,477 |
| Noncurrent version bytes | 261,472 | 806,760 |
| Object versions written | 45 | 749 |
| PUT requests | 45 | 749 |
| HEAD requests | 3 | 0 |
| GET requests | 6 | 0 |

The paced seed delta therefore measured 3,319.239 current bytes, 403.380
noncurrent bytes, 0.357 current objects, and 0.3745 PUT requests per added
server. Linear
extrapolation to 1,000,000 servers, retaining the fixed fixture overhead, is:

- 3,319,418,876 current bytes (3.319 decimal GB),
- 403,641,472 noncurrent bytes (0.404 decimal GB before seven-day expiry),
- about 357,029 current objects and 374,545 versions,
- about 374,500 seed PUTs.

At the current us-east-1 S3 Standard rates used by the deployment calculator
($0.023/GB-month and $0.005/1,000 PUTs), the extrapolated current plus
noncurrent storage is about $0.0856/month and the initial one-million-resource
seed PUTs about $1.8725 once. GET/LIST traffic and internet egress remain
workload-dependent and are not included in those two figures.

This is a compatibility and amplification measurement, not a promise that
MinIO latency equals S3 or that amplification remains perfectly linear. The
production acceptance plan records actual bucket bytes, object versions,
request metrics, heap/RSS, CPU credits, and query latency before and after the
one-million-resource seed. Any materially worse result returns to the operator
sizing/cost approval gate.
