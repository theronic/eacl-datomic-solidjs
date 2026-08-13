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

Each alarm sends both `ALARM` and `OK` transitions through SNS to a small
Lambda notifier. A CloudWatch dashboard graphs CPU, credits, memory, instance
status, and public endpoint status.

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

Create the stack initially with `AlarmActionsEnabled=false`, insert the token,
and install the pinned arm64 CloudWatch Agent:

```bash
infra/scripts/install-cloudwatch-agent.sh "$EACL_INSTANCE_HOST" \
  "$EACL_SSH_PRIVATE_KEY"
```

Inspect the CloudFormation change set before execution. The monitoring stack
must contain no `AWS::EC2::Instance`, EIP, S3 bucket, or Route53 record. Update
the stack with `AlarmActionsEnabled=true` only after the memory metric and
Telegram destination are ready.

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
```

Rotate the bot token by passing the replacement to
`store-telegram-token.sh`; no stack or EC2 update is required. CloudFormation
retains the secret on monitoring-stack deletion to prevent accidental
credential loss, so final teardown must explicitly schedule its deletion.

Gross monthly list-price exposure before account-level free tiers is about
`$5.20`: five standard alarms (`$0.50`), one custom memory metric (`$0.30`),
one dashboard (`$3.00`), one Secrets Manager secret (`$0.40`), and the HTTPS
health-check feature (`$1.00`). This account currently has two dashboards and
one metric alarm, so the third dashboard and five added alarm metrics fit the
published CloudWatch free tier; if the custom-metric allowance is also unused,
expected incremental recurring cost is about `$1.40` plus negligible SNS,
Lambda, Secrets Manager API, and 14-day notifier-log usage.
