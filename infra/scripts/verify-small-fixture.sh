#!/usr/bin/env bash
set -euo pipefail

host="${1:?instance IP or host required}"
ssh_key="${2:?SSH private-key path required}"
test -f "$ssh_key"

ssh -i "$ssh_key" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new \
  "ubuntu@$host" 'admin_token="$(sudo sed -n "s/^EACL_DATAHIKE_DEMO_ADMIN_TOKEN=//p" /etc/eacl-datahike-demo/eacl-datahike-demo.env)"; test -n "$admin_token"; ADMIN_TOKEN="$admin_token" python3 -' <<'PY'
import json
import os
import urllib.error
import urllib.request

base = "http://127.0.0.1:8088"
admin_token = os.environ["ADMIN_TOKEN"]


def request(method, path, payload=None, token=None):
    body = None if payload is None else json.dumps(payload).encode()
    headers = {"accept": "application/json"}
    if body is not None:
        headers["content-type"] = "application/json"
    if token is not None:
        headers["authorization"] = f"Bearer {token}"
    req = urllib.request.Request(base + path, data=body, headers=headers,
                                 method=method)
    try:
        response = urllib.request.urlopen(req, timeout=35)
    except urllib.error.HTTPError as error:
        response = error
    raw = response.read()
    parsed = json.loads(raw) if raw else None
    return response.status, parsed


def expect_status(expected, response):
    status, payload = response
    assert status == expected, (expected, status, payload)
    return payload


health = expect_status(200, request("GET", "/api/health"))
assert health["data"]["status"] == "ready"
assert health["data"]["datahike"]["storeBackend"] == "s3"

bootstrap = expect_status(200, request("GET", "/api/bootstrap"))["data"]
assert bootstrap["totals"] == {
    "servers": 48, "users": 19, "accounts": 4, "teams": 8, "vpcs": 4
}
assert bootstrap["capabilities"] == {
    "schemaWrite": False, "seedWrite": False, "cacheEvict": False
}

schema_before = expect_status(200, request("GET", "/api/schema"))["data"]["source"]
expect_status(401, request("POST", "/api/cache/evict", {}))
expect_status(401, request("POST", "/api/cache/evict", {}, "wrong"))
expect_status(200, request("POST", "/api/cache/evict", {}, admin_token))
expect_status(422, request("PUT", "/api/schema",
                           {"source": "definition broken {"}, admin_token))
schema_after = expect_status(200, request("GET", "/api/schema"))["data"]["source"]
assert schema_after == schema_before
expect_status(400, request("POST", "/api/seed", {"serverCount": 0}, admin_token))
seed = expect_status(200, request("GET", "/api/seed"))["data"]
assert seed["status"] == "ready" and seed["totalServers"] == 48
expect_status(413, request("PUT", "/api/schema", {"source": "x" * 70000},
                           admin_token))

lookup = {
    "subject": {"type": "user", "id": "super-user"},
    "resourceType": "server", "permission": "view",
    "pageSize": 10, "cache": True,
}
first = expect_status(200, request("POST", "/api/eacl/lookup-resources", lookup))["data"]
assert len(first["items"]) == 10 and first["pageInfo"]["hasNextPage"] is True
second_query = dict(lookup, after=first["pageInfo"]["endCursor"])
second = expect_status(200, request("POST", "/api/eacl/lookup-resources",
                                    second_query))["data"]
assert [item["id"] for item in first["items"]] != [item["id"] for item in second["items"]]

count_query = dict(lookup)
count_query.pop("pageSize")
count_query["countLimit"] = 50000
count = expect_status(200, request("POST", "/api/eacl/count-resources",
                                   count_query))["data"]
assert count == {"count": 48, "limit": 50000, "truncated": False}

subjects = expect_status(200, request("POST", "/api/eacl/lookup-subjects", {
    "resource": {"type": "server", "id": "account-0-server-0"},
    "permission": "view", "subjectType": "user", "pageSize": 10,
    "cache": True,
}))["data"]
assert subjects["items"]

relationships = expect_status(200, request("POST", "/api/eacl/read-relationships", {
    "subject": {"type": "account", "id": "account-0"},
    "resourceType": "server", "relation": "account",
    "authorizationSubject": {"type": "user", "id": "super-user"},
    "permission": "view", "pageSize": 10, "cache": True,
}))["data"]
assert len(relationships["items"]) == 10

permission = expect_status(200, request("POST", "/api/eacl/check-permission", {
    "subject": {"type": "user", "id": "user-1"},
    "resource": {"type": "account", "id": "account-0"},
    "permission": "admin", "cache": True,
}))["data"]
assert permission["allowed"] is True

mismatch = dict(lookup, subject={"type": "user", "id": "user-1"},
                after=first["pageInfo"]["endCursor"])
mismatch_payload = expect_status(409, request("POST", "/api/eacl/lookup-resources",
                                             mismatch))
assert mismatch_payload["error"]["code"] == "invalid-cursor"

print(json.dumps({
    "status": "small-fixture-contract-passed",
    "servers": 48,
    "firstPage": len(first["items"]),
    "secondPage": len(second["items"]),
    "adminAuthentication": "passed-without-secret-output",
}))
PY
