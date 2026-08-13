# Cost controls and emergency capacity mode

## Cost observed through 2026-08-13

Cost Explorer posts usage with a delay. For 2026-08-12, the `us-east-1` S3
line items showed 1,093,059 PUTs (`$5.465295`), 212,196 GETs (`$0.084878`), and
about `$0.022` of LIST/version-LIST requests. The preceding eleven-day baseline
in the region was zero or one request per day, so the `$5.57` request spike is
attributable to the demo seed with high confidence. Storage is only about
`$0.011/day` at the current 14.603 GB current-object footprint.

The EC2 runtime reconstructed from CloudTrail was 0.994 hours of `t4g.medium`,
16.826 hours of the original `t4g.large`, and then a replacement `t4g.large`.
At the audit time, compute plus 14.225 charged unlimited-mode surplus credits
was about `$1.35`. Associated 20 GiB gp3 and public IPv4 usage added about
`$0.045` and `$0.103`, respectively. Current-day Cost Explorer values remain
estimates until AWS finishes posting them.

## Request run-rate alarms

The application stack enables S3 request metrics only for the stable Datahike
store prefix. The monitoring stack sends Telegram warning and critical
transitions for GETs and PUTs. See [monitoring.md](monitoring.md) for exact
thresholds and monitoring cost.

These alarms limit detection time; they do not themselves stop traffic. A
single 5-minute critical period costs only about `$0.0125` in the request class
that triggered it, even though its continuous run rate would exceed
`$100/month`.

## Automatic capacity response

The deployment uses a two-stage, explicitly armed circuit breaker:

1. On either critical S3 request alarm, a dedicated least-privilege Lambda
   invokes a parameterless, stack-owned Systems Manager document on only the
   demo instance. The document contains only
   `/usr/local/sbin/eacl-capacity-suspend`.
2. The command atomically switches Caddy to a checked-in capacity page, reloads
   Caddy, and stops `eacl-datahike-demo`. HTML requests receive a human-readable
   `503 Capacity exceeded` page; API requests receive a bounded JSON `503`.
3. Recovery is deliberately manual: an operator runs one documented command
   that starts the application, waits for loopback health, restores the normal
   Caddy configuration, and reloads Caddy.

The controller ignores warning alarms, `OK` transitions, malformed messages,
and all alarm names except the two checked-in critical request alarms. Its role
cannot use generic shell documents, target any other instance, stop, start,
reboot, or terminate EC2. The EC2 role has only the SSM control-channel actions;
it cannot read the retained Telegram secret.

The controller waits for SSM to report command success. A failed or
unconfirmed command fails the Lambda event, which is retried twice for up to
one hour and trips `demo-eacl-datahike-capacity-controller-failed`. The
operator receives an explicit Telegram alert instead of a false success.

This stops public S3 reads while preserving a useful error page. It does not
save EC2 compute cost because Caddy remains on the instance. A second, much
higher billing guard can stop EC2 through a Lambda `StopInstances` call, but a
stopped instance cannot serve an error page. Keeping a friendly page while EC2
is stopped requires a separate always-on edge/fallback service such as
CloudFront, which adds architecture and cost.

Do not automatically recover when a request alarm returns to `OK`: stopping the
application itself makes traffic fall and would immediately satisfy the alarm,
creating an unsafe stop/start loop. Do not terminate the instance; stopping is
recoverable, while termination would replace the root volume and instance
identity.

## Installation and arming

Deploy the monitoring stack with `CapacityActionsEnabled=false` first. Install
the root-owned configs, scripts, and pinned arm64 SSM agent on the host:

```bash
infra/scripts/install-capacity-controls.sh "$EACL_INSTANCE_HOST" \
  "$EACL_SSH_PRIVATE_KEY"
```

Wait until `aws ssm describe-instance-information` reports the instance
`Online`. Inspect a second CloudFormation change set that changes only
`CapacityActionsEnabled` to `true`, then execute it. The setting is independent
of `AlarmActionsEnabled`: the latter controls SNS publication by the alarms,
so both must be `true` in production.

## Suspension and recovery

Capacity mode is intentionally sticky. An S3 alarm returning to `OK` does not
recover the app. Inspect the triggering alarm and current request metrics, then
resume through SSH:

```bash
ssh -i "$EACL_SSH_PRIVATE_KEY" ubuntu@"$EACL_INSTANCE_HOST" \
  'sudo /usr/local/sbin/eacl-capacity-resume'
```

The recovery command validates the normal Caddy configuration, starts the app,
waits up to three minutes for loopback health, and only then exposes it. If the
app does not become healthy or Caddy cannot reload, the script stops the app
and retains the capacity page.

For an acceptance test, temporarily set exactly one critical S3 alarm to
`ALARM`. Verify the capacity-controller log includes a Run Command ID, the app
service is stopped, and both checks pass:

```bash
infra/tests/capacity-proxy.sh https://demo.eacl.dev
```

Set the alarm to `OK` and confirm capacity mode remains active before running
the manual recovery command. This test interrupts the public demo and sends
real Telegram `ALARM` and `OK` notifications.
