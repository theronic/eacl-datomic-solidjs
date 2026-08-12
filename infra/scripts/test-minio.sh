#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
container_name=eacl-datahike-minio-probe
image=minio/minio@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e
cleanup() { docker rm -f "$container_name" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

docker run --name "$container_name" --detach --publish 127.0.0.1:19000:9000 \
  --env MINIO_ROOT_USER=minioadmin --env MINIO_ROOT_PASSWORD=minioadmin123 \
  "$image" server /data >/dev/null
for attempt in $(seq 1 60); do
  if curl --fail --silent http://127.0.0.1:19000/minio/health/ready >/dev/null; then
    break
  fi
  if [ "$attempt" -eq 60 ]; then docker logs "$container_name"; exit 1; fi
  sleep 1
done

export AWS_ACCESS_KEY_ID=minioadmin
export AWS_SECRET_ACCESS_KEY=minioadmin123
export AWS_DEFAULT_REGION=us-east-1
aws --endpoint-url http://127.0.0.1:19000 s3api create-bucket \
  --bucket eacl-datahike-probe >/dev/null
aws --endpoint-url http://127.0.0.1:19000 s3api put-bucket-versioning \
  --bucket eacl-datahike-probe --versioning-configuration Status=Enabled

cd "$repo_root/server"
EACL_DATAHIKE_DEMO_MINIO_ENDPOINT=http://127.0.0.1:19000 \
EACL_DATAHIKE_DEMO_MINIO_BUCKET=eacl-datahike-probe \
EACL_DATAHIKE_DEMO_MINIO_ACCESS_KEY=minioadmin \
EACL_DATAHIKE_DEMO_MINIO_SECRET_KEY=minioadmin123 \
clojure -M:test -m cognitect.test-runner -n eacl-datahike-demo.storage-test
