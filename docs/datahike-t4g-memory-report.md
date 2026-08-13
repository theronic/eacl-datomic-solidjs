# Datahike S3 bulk-seed memory report

- **Audience:** Christian Weilbach, Datahike maintainer
- **Observed:** 2026-08-12 to 2026-08-13
- **Workload:** EACL v8 Datahike demo, 1,000,048 permissioned resources
- **Production URL:** <https://demo.eacl.dev/datahike/>

## Executive summary

We increased the loader host from `t4g.medium` (2 vCPU, 4 GiB) to
`t4g.large` (2 vCPU, 8 GiB) because the bulk seed approached the medium's
memory limit. One medium loader process reached a 3.2 GiB systemd memory peak,
with no swap, and our host-available-memory guard stopped further loading at
roughly 700 MiB of headroom. No kernel OOM or Datahike crash occurred; these
were deliberate safety stops with committed S3 state recovered after each
restart.

On the large instance, seed processes using the approved higher-throughput
loader profile repeatedly peaked at 5.4–5.6 GiB. This is not an apples-to-apples
comparison with the medium profile, because the large loader also used a 5 GiB
heap and larger transactions. It does show that the loader configuration that
finished in an acceptable time could not fit on a 4 GiB host. The resize let us
complete the data load without weakening the memory guard.

This does **not** yet prove that steady reads require `t4g.large`. A fresh
normal-profile JVM (`-Xms256m -Xmx3g`) reopening the completed database uses
about 1.8 GiB RSS on the large host. We are deliberately keeping the instance
large while latency is evaluated; `t4g.medium` remains a plausible permanent
read size, subject to a clean downsize test with memory, latency, error-rate,
and CPU-credit gates.

## Software and storage topology

- Ubuntu arm64 on an EC2 T4g burstable instance in `us-east-1`.
- The application and Datahike writer live in one JVM; there is no separate
  transactor.
- Datahike `0.8.1759`, `konserve-s3` `0.1.37`, and the EACL Datahike adapter
  `8.0.0-SNAPSHOT` built from EACL PR 115.
- A private, versioned S3 bucket in the same region is accessed through the
  EC2 instance role and an S3 gateway endpoint. No AWS access keys are stored
  on the host.
- The database uses Datahike's default
  `:index :datahike.index/persistent-set`, with
  `:index-config {:diff-buf-size 256}`, `:fuse-index-roots? true`,
  `:keep-history? false`, and `:commit-graph? false`.
- The store had one branch, `#{:db}`, after the import. We did not run an
  experimental storage GC.

Diff buffering and root fusion were therefore active during the production
load. They may have reduced amplification relative to the unfused path, but
the resulting object count and memory profile are still unexpectedly high.

## Import strategy

We first verified a 48-resource fixture, permissions, pagination, restart
recovery, and S3 persistence. The bulk importer then used bounded Datahike
transactions and externally bounded jobs, verifying the committed total after
each process restart.

We tried smaller transactions and pauses first. Slowing allocation reduces
instantaneous CPU and write pressure, but it did not give enough confidence in
the cumulative process footprint: JVM committed/resident memory and seed-path
state continued to grow across transactions. A delay between transactions
cannot force unreachable heap pages back to the OS, and it cannot fix state
retained for the life of the connection. Process restarts reliably reclaimed
that footprint while leaving already committed S3 data intact.

After the operator approved the temporary resize, the loader used:

- `t4g.large`;
- a seed-only `-Xms512m -Xmx5g` JVM profile;
- 500-resource transactions;
- no artificial inter-transaction delay;
- externally bounded jobs with RSS and host-available-memory stop conditions;
- a clean process restart and committed-total check between jobs.

The final total was exactly 1,000,048 resources. We removed the seed-only JVM
override afterward and restarted into the normal 3 GiB read profile.

## Measured memory evidence

The following process peaks come from systemd's cgroup accounting. Every
record reported `0B memory swap peak`.

| Host / phase | Process memory peak | Interpretation |
| --- | ---: | --- |
| `t4g.medium`, loader | 3.2 GiB | Only about 0.8 GiB remains for the kernel, agents, proxy, and transient native/off-heap demand on a 4 GiB host. |
| `t4g.medium`, other bounded loader runs | 1.8–2.7 GiB | Footprint varied with job progress and restart point; restart reclaimed memory. |
| `t4g.large`, higher-throughput loader | 5.4–5.6 GiB, repeatedly | The loader profile that completed the import exceeds total medium memory; this profile also had a larger heap and transactions. |
| `t4g.large`, clean normal read JVM | about 1.8 GiB RSS | Seed peak is not representative of steady reads. |

The safety cutoff on medium was about 700 MiB host-available memory. It was
chosen before the load precisely to avoid discovering the true limit through
an OOM kill. Consequently there is no OOM stack trace: the lack of an OOM is a
result of the guard, not evidence of unused capacity.

A thread dump from one large seed JVM reported G1 heap reserved at about
5.0 GiB, committed at about 3.18 GiB, and used at about 2.05 GiB. A later clean
read JVM's `/proc/<pid>/smaps_rollup` showed approximately 1.81 GiB PSS, of
which approximately 1.78 GiB was anonymous memory. This points primarily to
JVM heap/native residency rather than Linux filesystem page cache.

## Is delayed GC the cause of high RSS?

It is part of the explanation, but the current evidence does not justify
calling it the sole cause.

G1 can keep committed regions resident after objects become unreachable, and
Linux RSS does not immediately fall just because the live heap has fallen.
That explains why RSS is a poor proxy for live data and why a process restart
causes a much sharper drop than waiting between transactions. The large seed
process nevertheless demonstrated a substantial used heap as well as a large
commit, so there may also be genuinely live importer, Datahike connection,
Konserve client, index-diff, or cache state retained across transactions.

We did not capture a seed-time heap dump or Native Memory Tracking baseline
before the completed import. The production JRE also lacks `jcmd`, so we do
not yet have enough attribution to split Java heap, class metadata, thread
stacks, direct buffers, and other native allocations at the 5.6 GiB peak.

## CPU credits and responsiveness

Memory was not the only limit. During the sustained import, CPU utilization
reached about 96%, and the T4g CPU credit balance reached zero. The application
then became slow enough that client timeouts and repeated requests were an
operational risk even though cooperative cancellation was subsequently added.

We temporarily changed T4g credit mode to `unlimited` for the approved import
window and returned it to `standard` afterward. This avoids credit throttling
during a one-off loader run, but it is not a substitute for measuring the
steady read workload in standard mode.

## Completed-store size and read behavior

After the import, the versioned S3 bucket contained:

| Measurement | Result |
| --- | ---: |
| Current objects | 1,083,511 |
| Current bytes | 14,602,949,290 bytes (13.60 GiB) |
| All versions | 1,093,050 |
| All-version bytes | 14,860,894,463 bytes (13.84 GiB) |
| Noncurrent bytes | 257,945,173 bytes |

The noncurrent versions expire after seven days. Current immutable objects do
not, so the 14.60 GB figure is not explained by S3 versioning alone.

A fresh JVM took about 3 minutes 45 seconds to open the store and become HTTP
ready. The canonical cold page plus bounded 50,000-item count prewarm took
about 216 seconds in one acceptance run. Once warm, the application reported
about 5.5 ms for the default page and 8.7 ms for the bounded count. The EACL
completed-answer and continuation caches were far below their configured
limits and recorded no capacity evictions, so enlarging those caches would not
address the cold traversal or import memory peak.

## Why we resized instead of only slowing the seed

Smaller transactions and delays remain useful for bounding individual work,
but they solve a different problem from a cumulative or sticky process
footprint. At the medium's observed 3.2 GiB process peak, normal native and OS
variance could consume the remaining margin. The later 5.4–5.6 GiB peaks prove
that the chosen higher-throughput loader can demand more memory than exists on
medium; they do not prove that every slower loader profile must do so.

It might be possible to complete the same import on medium using much smaller
jobs and more frequent JVM restarts. We did not prove that impossible. We
instead chose the operationally safer and faster route: temporarily provide a
measured memory envelope, preserve explicit cutoffs, finish the one-off load,
and size the permanent machine separately from a clean read JVM.

## Questions for Datahike

We would value guidance on the following:

1. Is retention of index diffs, past DB values, pending Konserve operations,
   or connection-local state across many small transactions expected during
   an import of this shape?
2. Are `:diff-buf-size 256` and `:fuse-index-roots? true` sufficient to activate
   the current diff-buffering/root-fusion path in `0.8.1759`, or is another
   feature flag or importer API required?
3. What seed-time metrics, heap classes, or Native Memory Tracking categories
   would best distinguish expected G1 commitment from unintended retention?
4. Is there a supported way to checkpoint/reopen the connection without a
   full application process restart while guaranteeing that importer state is
   released?
5. Is roughly 1.08 million current S3 objects and 14.60 GB plausible for this
   database, and which forthcoming import/export or compaction path should we
   evaluate before repeating the load?

## Restricted diagnostic access

An SSH account named `christian` is installed on the instance with Christian's
public key (fingerprint `SHA256:/1xl3h2C1xyVZZfzxm8B2zFgOrYn/Hvb2P6Xlm8tt0g`).
It is deliberately not the operator's `ubuntu` account and has no general
`sudo` access. The account:

- cannot read the root-owned application environment, operator home, process
  environment, or AWS configuration;
- is blocked by UID-specific firewall policy from EC2 Instance Metadata, so it
  cannot obtain instance-role credentials;
- is blocked from the loopback nREPL port over IPv4 and IPv6, while operator
  access to nREPL remains available;
- has SSH forwarding disabled;
- may run only `sudo /usr/local/sbin/eacl-memory-report`, a root-owned fixed
  diagnostic that reports cgroup/JVM memory, `/proc` memory summaries, host
  pressure, and recent application logs.

Port 22 accepts key-authenticated SSH from any public IPv4 address. Password
and root login remain disabled, and the per-user restrictions above apply
regardless of source address. The connection is:

```bash
ssh christian@54.163.189.23
sudo /usr/local/sbin/eacl-memory-report
```

There are no long-lived AWS credentials on the instance. S3 authorization is
provided to the application through the EC2 instance role, and the metadata
credential endpoint is inaccessible to the restricted diagnostic user.
