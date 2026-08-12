# Remote production deployment runbook

This runbook deploys one EACL/Datahike application JVM on an arm64 EC2
instance, backed by a private S3 bucket in the same AWS region. Caddy terminates
free ACME TLS and proxies `/datahike/*` to loopback Jetty. It contains no account
IDs, hostnames, addresses, store IDs, credentials, or operator key paths.

## Architecture and security invariants

- A single process owns the Datahike writer and EACL client.
- S3 and EC2 are in one region; an S3 gateway endpoint avoids NAT charges.
- EC2 receives S3 access through its instance role. Never put AWS access keys in
  the service environment.
- Only ports 80 and 443 are public. SSH 22 is restricted to one approved `/32`.
- Jetty 8088 and nREPL 7888 bind to `127.0.0.1` and have no security-group
  ingress.
- The root-owned application environment is mode `0600`. The signing key,
  admin token, and Datahike store ID remain stable across restarts.
- The S3 bucket is private, encrypted, versioned, retained by CloudFormation,
  and expires noncurrent versions after seven days.
- Public Caddy routes deny schema writes, seeding, and cache eviction even
  though the application independently enforces the admin bearer token.

## Capacity decisions

The template defaults to `t4g.medium` (2 vCPU, 4 GiB) for steady reads. It is
the smallest candidate compatible with the normal `-Xmx3g` JVM plus operating
system headroom. `t4g.small` has only 2 GiB and is rejected for this JVM
profile.

The measured reference workload used 1,000,048 permissioned server resources:

| Measurement | Result |
| --- | ---: |
| Clean read JVM RSS on `t4g.large` | about 1.75 GiB |
| Host memory available on `t4g.large` | about 5.45 GiB |
| Warm default page, application time | about 5.5 ms |
| Warm bounded count, application time | about 8.7 ms |
| Cold bounded prewarm | about 3.9 minutes |
| S3 current storage after bulk seed | about 14.60 GB / 1.08M objects |

The `t4g.medium` choice remains conditional until the same clean-JVM read test
passes after an in-place downsize. Accept it only with at least 20% available
memory, no sustained CPU-credit depletion, no unhandled 5xx responses, and a
warmed default-page p95 no greater than 250 ms.

Bulk loading is not a permanent sizing signal. If needed, temporarily use
`t4g.large`, a seed-only `-Xmx5g` override, and bounded batches with RSS and
available-memory cutoffs. Any resize or switch to `unlimited` CPU credits is a
separate cost decision; restore the normal service unit and `standard` credits
after loading before measuring reads.

Datahike is configured with persistent-set diff buffering, root fusion,
`keep-history? false`, and `commit-graph? false`. These reduce write
amplification but do not reclaim unreachable immutable index nodes. Do not run
experimental storage GC or assume the small MinIO extrapolation is linear at
one million resources. Measure S3 current objects/bytes and noncurrent versions
after each scale test, and evaluate a verified export/direct-index import when
the corresponding Datahike release is available.

## Prepare an untracked deployment environment

Copy the example and fill it locally. `infra/deployment.env` is ignored by Git.

```bash
cp infra/deployment.env.example infra/deployment.env
chmod 600 infra/deployment.env
set -a
source infra/deployment.env
set +a
```

Choose a globally unique bucket, a new UUID, an availability zone within the
selected region, the public DNS name without a trailing dot for Caddy, and an
absolute public-key path. Record the current A value for guarded rollback. No
secret key material belongs in this file.

## Validate and approve the package

The validation commands are read-only: caller identity, AMI lookup, pricing,
bucket availability, template validation, and local proxy/script checks.

```bash
infra/scripts/validate-plan-read-only.sh
infra/scripts/render-approval.sh
```

Confirm the rendered resource names, instance type, region, CIDR, key
fingerprint, bucket, store ID, DNS change, and current prices with the operator.
Then bind approval to the exact account, region, and stack:

```bash
export EACL_AWS_MUTATION_APPROVED="${EACL_AWS_ACCOUNT}:${EACL_AWS_REGION}:${EACL_STACK_NAME}"
infra/scripts/create-stack.sh
```

The script rechecks caller identity, public CIDR, SSH fingerprint, AMI,
template checksum, and bucket availability immediately before creation.

## Install and accept the 48-resource fixture

Read the stack outputs to obtain the Elastic IP, bucket, and store ID:

```bash
infra/scripts/stack-read-only.sh
npm ci --prefix client
npm run verify

infra/scripts/install-host.sh "$EACL_INSTANCE_HOST" \
  "$EACL_SSH_PRIVATE_KEY" "$EACL_BUCKET_NAME" "$EACL_STORE_ID" \
  "$EACL_AWS_REGION"
infra/scripts/deploy-artifact.sh "$EACL_INSTANCE_HOST" \
  "$EACL_SSH_PRIVATE_KEY"
infra/scripts/verify-small-fixture.sh "$EACL_INSTANCE_HOST" \
  "$EACL_SSH_PRIVATE_KEY"
```

Before a large seed, restart the application and reboot EC2. Health, totals,
pagination, permission checks, and the 48-resource fixture must survive both.
Confirm `ss` shows 8088 and 7888 only on loopback. Test nREPL only through SSH:

```bash
ssh -i "$EACL_SSH_PRIVATE_KEY" -L 17888:127.0.0.1:7888 \
  "ubuntu@$EACL_INSTANCE_HOST"
clj-nrepl-eval -p 17888 "(+ 20 22)"
```

## Publish DNS and TLS

Keep the HTTP-only Caddy configuration until the public A record resolves to
the new Elastic IP. Bind publication approval to the exact DNS name and IP:

```bash
export EACL_DNS_MUTATION_APPROVED="${EACL_DNS_NAME}:${EACL_INSTANCE_HOST}"
infra/scripts/publish-dns.sh "$EACL_INSTANCE_HOST"
```

After authoritative and public resolvers agree, enable HTTPS on the host:

```bash
ssh -i "$EACL_SSH_PRIVATE_KEY" "ubuntu@$EACL_INSTANCE_HOST" \
  "sudo bash /home/ubuntu/eacl-deploy/infra/scripts/enable-https.sh '$EACL_SITE_ADDRESS'"
```

Verify the certificate hostname, `/` redirect, `/datahike/` application,
assets, API health, SPA refresh, and public 403 responses for all administrative
mutation routes.

## Seed and read acceptance

Start with a small authorized seed and verify totals, permissions, restart
recovery, and public responsiveness. For a million-resource load, explicitly
provide all deployment endpoints; the script has no production defaults:

```bash
export EACL_PUBLIC_BASE="https://${EACL_SITE_ADDRESS}/datahike"
export EACL_SSH_HOST="ubuntu@${EACL_INSTANCE_HOST}"
export EACL_SSH_KEY="$EACL_SSH_PRIVATE_KEY"
export EACL_NREPL_PORT=17888
infra/scripts/seed-million-bounded.sh 1000048 20000
```

The loader stops on RSS or host-memory safety thresholds, verifies committed
totals after each restart, and never treats a client interruption as a request
to delete durable data. After loading:

1. remove the seed-only systemd drop-in;
2. restore `standard` credits;
3. start a clean normal-profile JVM;
4. verify exactly 1,000,048 servers from S3;
5. record cold and warm page/count latency, RSS, available memory, CPU credits,
   cache statistics, and S3 current/noncurrent storage;
6. resize to the steady candidate only after operator approval and repeat the
   clean-JVM gates.

## Operations and rollback

```bash
infra/scripts/remote-health.sh "$EACL_INSTANCE_HOST" "$EACL_SSH_PRIVATE_KEY"
infra/scripts/rollback-artifact.sh "$EACL_INSTANCE_HOST" \
  "$EACL_SSH_PRIVATE_KEY" <previous-artifact-sha256>
```

Artifact rollback changes only the checksummed jar and restarts the service; it
does not delete S3 data. DNS rollback is separately guarded and only proceeds
if the current A value is the expected deployment IP:

```bash
export EACL_DNS_ROLLBACK_APPROVED="${EACL_DNS_NAME}:${EACL_PREVIOUS_A}"
infra/scripts/rollback-dns.sh "$EACL_INSTANCE_HOST"
```

CloudFormation retains the versioned bucket. Teardown therefore requires an
explicit decision to retain, export, or permanently empty it; application or
DNS rollback must never delete the bucket.
