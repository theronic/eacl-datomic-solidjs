# Production monitoring and Telegram alerts

Monitoring is isolated in the `demo-eacl-datahike-monitoring` CloudFormation
stack. It references the application instance and role by parameter but does
not own or update the EC2 resource, so deploying monitoring cannot replace the
application host.

## Signals and thresholds

| Alarm | Signal | Threshold |
| --- | --- | --- |
| Low CPU credits | `AWS/EC2 CPUCreditBalance` | at or below 24 credits for two 5-minute periods |
| High CPU | `AWS/EC2 CPUUtilization` | at or above 85% for three 5-minute periods |
| Memory pressure | `CWAgent mem_available_percent` | below 20% for five 1-minute periods; missing telemetry also breaches |
| Instance unresponsive | `AWS/EC2 StatusCheckFailed` | at least 1 for two 1-minute periods |
| Public service unavailable | Route53 HTTPS health check of `/datahike/api/health` | unhealthy for three 1-minute periods |
| S3 GET warning / critical | `AWS/S3 GetRequests`, scoped to the Datahike object prefix | 10,417 / 31,250 in one 5-minute period |
| S3 PUT warning / critical | `AWS/S3 PutRequests`, scoped to the Datahike object prefix | 834 / 2,500 in one 5-minute period |
| Capacity controller failure | `AWS/Lambda Errors` for the controller | at least 1 in one minute |

Each alarm sends both `ALARM` and `OK` transitions through SNS to a small
Lambda notifier. A separate controller consumes the same topic but acts only
on `ALARM` transitions from the two critical S3 request alarms. It uses SSM to
put the host into sticky capacity mode; see [cost-controls.md](cost-controls.md).
A CloudWatch dashboard graphs CPU, credits, memory, instance status, public
endpoint status, and scoped S3 requests.

The controller waits for the SSM command to finish successfully. A failed or
unconfirmed command fails the Lambda event, which is retried twice for up to
one hour. The separate controller-error alarm sends Telegram `ALARM` and `OK`
transitions so a failed circuit breaker cannot be mistaken for a successful
suspension.

At the current `us-east-1` rates of `$0.0004/1,000` GETs and `$0.005/1,000`
PUTs, either warning threshold is a `$0.05/hour` or `$36.50/month` request-cost
run rate if continuous. Either critical threshold is `$0.15/hour` or
`$109.50/month`. The warning GET threshold is about 2.6 canonical cold pages
within five minutes at the observed 3,935 GETs per page. These are run-rate
alarms, not billing totals, and S3 documents request metrics as near-real-time
best-effort telemetry rather than complete billing records.

The application stack owns one S3 request-metrics configuration named
`DatahikeStore`. It filters on `<store-id>_`, so unrelated bucket-management
requests and any future non-Datahike objects do not affect the alarms.

## Secret handling

The CloudFormation stack creates an empty, retained Secrets Manager secret
named `demo/eacl/datahike/telegram`. The token is never a CloudFormation
parameter, Lambda environment variable, EC2 file, EC2 environment variable,
or command-line argument. Supply it over standard input after stack creation:

```bash
printf '%s' "$TELEGRAM_BOT_TOKEN" | \
  infra/scripts/store-telegram-token.sh <secret-arn>
```

The local storage helper streams the token directly to the AWS CLI through
standard input and never places it in an argument, temporary file, or
deployment file. The notifier role can only retrieve that secret and write its
own log stream.
The token necessarily exists transiently in the short-lived notifier process
while it constructs the Telegram HTTPS request; no Telegram integration can
authenticate without transiently using the credential. The function does not
cache or log it. The EC2 instance role cannot retrieve the secret.

The numeric Telegram chat ID is not a credential and is an ordinary stack
parameter. Send `/start` to the bot, then use `getUpdates` from a trusted
operator environment to find the private chat ID. Do not use a bot username as
the destination.

## Deployment gate

Create the stack initially with both `AlarmActionsEnabled=false` and
`CapacityActionsEnabled=false`, insert the token, and install the pinned arm64
CloudWatch Agent:

```bash
infra/scripts/install-cloudwatch-agent.sh "$EACL_INSTANCE_HOST" \
  "$EACL_SSH_PRIVATE_KEY"
```

Inspect the CloudFormation change set before execution. The monitoring stack
must contain no `AWS::EC2::Instance`, EIP, S3 bucket, or Route53 record. Update
the stack with `AlarmActionsEnabled=true` only after the memory metric and
Telegram destination are ready. Install and test the SSM control path before
separately setting `CapacityActionsEnabled=true` as documented in
[cost-controls.md](cost-controls.md).

## Acceptance test

The test waits for real memory telemetry, temporarily sets the high-CPU alarm
to `ALARM`, verifies the Lambda log confirms Telegram accepted the message,
and returns the alarm to `OK`:

```bash
infra/scripts/test-monitoring.sh demo-eacl-datahike-monitoring
```

Confirm both test messages appear in Telegram and that all alarms settle to
`OK`. The test does not generate CPU or memory pressure and does not interrupt
the demo.

## Operations

```bash
aws --profile 843761893873_Petrus_Prod --region us-east-1 \
  cloudwatch describe-alarms --alarm-name-prefix demo-eacl-datahike-

aws --profile 843761893873_Petrus_Prod --region us-east-1 \
  logs tail /aws/lambda/demo-eacl-datahike-telegram-notifier --since 1h

aws --profile 843761893873_Petrus_Prod --region us-east-1 \
  logs tail /aws/lambda/demo-eacl-datahike-capacity-controller --since 1h
```

Rotate the bot token by passing the replacement to
`store-telegram-token.sh`; no stack or EC2 update is required. CloudFormation
retains the secret on monitoring-stack deletion to prevent accidental
credential loss, so final teardown must explicitly schedule its deletion.

Gross monthly list-price exposure before account-level free tiers is about
`$5.70`: ten standard alarms (`$1.00`), one custom memory metric (`$0.30`),
one dashboard (`$3.00`), one Secrets Manager secret (`$0.40`), and the HTTPS
health-check feature (`$1.00`). This account currently has two dashboards and
two pre-existing metric alarms. The demo's ten alarms bring the account to
twelve alarm metrics, so two are expected to be billable at about `$0.20/month`.
S3 emits only the request-metric series applicable to operations that occur;
successful GET/PUT/LIST/HEAD traffic is expected to keep the combined custom
metric count close to the ten-metric free allowance. Allow up to about
`$0.60/month` if error or additional request series push two metrics beyond the
allowance. Expected incremental recurring monitoring cost is therefore about
`$1.60-$2.20`, plus negligible SNS, Lambda, Secrets Manager API, and 14-day
notifier-log usage.
