# Local Datahike with MinIO

This runbook starts the full application against a persistent, loopback-only
MinIO bucket. It exercises the same Datahike Konserve S3 backend used in remote
deployments without using an AWS account.

## Requirements

- Docker,
- AWS CLI,
- JDK 26 and Clojure CLI,
- Node.js and npm.

The fixed credentials below are development-only credentials for a container
bound to `127.0.0.1`. Do not reuse them outside this local environment.

## Start MinIO

```bash
docker volume create eacl-datahike-minio
docker run --detach --name eacl-datahike-minio \
  --publish 127.0.0.1:19000:9000 \
  --publish 127.0.0.1:19001:9001 \
  --volume eacl-datahike-minio:/data \
  --env MINIO_ROOT_USER=minioadmin \
  --env MINIO_ROOT_PASSWORD=minioadmin123 \
  minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e \
  server /data --console-address :9001

until curl --fail --silent http://127.0.0.1:19000/minio/health/ready; do
  sleep 1
done
```

The S3 endpoint is `http://127.0.0.1:19000`; the optional console is
`http://127.0.0.1:19001`.

## Create the bucket

```bash
export AWS_ACCESS_KEY_ID=minioadmin
export AWS_SECRET_ACCESS_KEY=minioadmin123
export AWS_DEFAULT_REGION=us-east-1

aws --endpoint-url http://127.0.0.1:19000 s3api create-bucket \
  --bucket eacl-datahike-local
aws --endpoint-url http://127.0.0.1:19000 s3api put-bucket-versioning \
  --bucket eacl-datahike-local \
  --versioning-configuration Status=Enabled
```

`create-bucket` reports `BucketAlreadyOwnedByYou` on a later run; that is safe
to skip. Versioning makes local persistence and reconnect behavior closer to
the production topology.

## Run the application

Generate a store ID once and keep it with the local MinIO volume. A different
ID creates a different database in the same bucket.

```bash
export EACL_DATAHIKE_DEMO_STORE_BACKEND=s3
export EACL_DATAHIKE_DEMO_STORE_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
export EACL_DATAHIKE_DEMO_S3_BUCKET=eacl-datahike-local
export EACL_DATAHIKE_DEMO_S3_REGION=us-east-1
export EACL_DATAHIKE_DEMO_S3_ENDPOINT=http://127.0.0.1:19000
export EACL_DATAHIKE_DEMO_S3_PATH_STYLE_ACCESS=true
export EACL_DATAHIKE_DEMO_DATAHIKE_STORE_CACHE_SIZE=8192
export EACL_DATAHIKE_DEMO_DATAHIKE_SEARCH_CACHE_SIZE=0
export EACL_DATAHIKE_DEMO_SEED_IN_FLIGHT=4

npm run install:client
npm run dev:server
```

Start `npm run dev:client` in another terminal and open
<http://127.0.0.1:5173>. The first connection creates the database, schema, and
48-resource fixture. A later application restart must reuse the same
`EACL_DATAHIKE_DEMO_STORE_ID` and report the same totals.

The application deliberately relies on the standard AWS SDK credential chain;
there are no application-specific access-key environment variables. In this
local shell the SDK consumes the MinIO credentials exported above. Remote AWS
deployments use an EC2 instance role instead.

## Verify persistence

```bash
curl --fail --silent http://127.0.0.1:8088/api/health | jq
curl --fail --silent http://127.0.0.1:8088/api/bootstrap | jq '.data.totals'

# Stop and restart only the application, preserving MinIO and its volume.
# Then repeat both requests and run:
EACL_DATAHIKE_DEMO_E2E_URL=http://127.0.0.1:5173 npm run test:e2e
```

For the disposable storage compatibility suite instead of a persistent
development environment, run `infra/scripts/test-minio.sh`. It creates and
removes its own container and test database.

Before testing storage reclamation, read
[`storage-maintenance.md`](storage-maintenance.md). Never point a disposable
MinIO GC experiment at the production store ID or bucket.

## Stop or remove the local environment

```bash
docker stop eacl-datahike-minio
docker start eacl-datahike-minio
```

Removing the container does not remove the named volume. Deleting
`eacl-datahike-minio` with `docker volume rm` permanently deletes the local
database and should only be done deliberately.
