# Verification record

## Application

The current verification contract is:

| Check | Expected result |
| --- | --- |
| ESLint | pass with zero warnings |
| Client unit suite | 7 files, 18 tests |
| Backend suite | 29 tests, 176 assertions |
| Local Datahike Playwright | 16 desktop/mobile cases |
| Production build | hashed `/datahike/assets/*` embedded in the uberjar |
| Reverse-proxy contract | redirects, prefix stripping, assets, API precedence, SPA refresh, mutation denials |

Run the application checks with:

```bash
npm run verify
EACL_DATAHIKE_DEMO_E2E_URL=http://127.0.0.1:5173 npm run test:e2e
```

The browser command expects both development processes to be active. The
separate reverse-proxy contract exercises the built `/datahike/` asset base.

The unreliable-backend browser scenario holds a Server Page 2 request open and
asserts that Page 1 resources, timings, page number, and controls remain visible
while the Next button spins. It injects HTTP 504, verifies that the retained
page remains beside the labeled Retry/Previous error, and publishes Page 2 only
after a successful retry. Unit coverage includes network
failure, the 35-second client deadline, cache invalidation, subject-page
loading, pagination failure, and explicit recovery from an expired EACL cursor.

## Storage and reconnect

`infra/scripts/test-minio.sh` runs the versioned MinIO compatibility suite. It
covers database creation, schema and fixtures, a paced seed, a real permission
query, release, reconnect with the same UUID, object/version measurement, and
cleanup. For a persistent developer instance, use
[`local-minio-runbook.md`](local-minio-runbook.md).

The two-process file-store preflight seeds in one JVM and reads in a new JVM.
Its measurements and the one-million-resource acceptance gates are recorded in
[`read-sizing.md`](read-sizing.md). MinIO compatibility does not prove AWS S3
latency or linear write amplification.

## Infrastructure

With an untracked `infra/deployment.env` loaded,
`infra/scripts/validate-plan-read-only.sh` verifies:

- shell syntax and executable bits;
- absence of private-key and AWS access-key material;
- CloudFormation validation and parameter summary in the selected account and
  region;
- an arm64 Ubuntu 24.04 AMI from SSM;
- availability of the proposed globally unique bucket;
- Caddy configuration and reverse-proxy behavior;
- the default `t4g.medium`, 20 GiB encrypted gp3 root, and `standard` CPU
  credits.

The production acceptance sequence additionally verifies the EC2 role
credential chain, private/versioned S3 behavior, restart and reboot recovery,
loopback-only Jetty/nREPL, SSH tunnelling, public TLS, public mutation denial,
the 48-resource fixture, and clean-JVM read behavior at the final dataset size.
The reusable procedure and rollback guards are in
[`deployment.md`](deployment.md).

No validation command grants permission to create, resize, publish DNS, or
delete retained storage. Those mutations each require the explicit approval
binding documented in the production runbook.
