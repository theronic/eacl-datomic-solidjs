import type { ApiSuccess, Bootstrap, CacheSnapshot, SchemaInfo } from "../src/types";

export const schemaSource = `definition user {}

definition account {
  relation owner: user
  permission view = owner
}

definition server {
  relation account: account
  permission view = account->view
}`;

export const schema: SchemaInfo = {
  source: schemaSource,
  resourceTypes: ["account", "server"],
  permissionsByType: { account: ["view"], server: ["view"] },
  childPaths: {
    account: [{ resourceType: "server", relation: "account" }],
    server: [],
  },
  nodes: [
    { id: "account", permissions: ["view"] },
    { id: "server", permissions: ["view"] },
  ],
  links: [{ source: "account", target: "server", label: "account" }],
  resourceCount: 2,
  relationCount: 2,
  permissionCount: 2,
  presets: [
    { id: "default", label: "Default", schema: schemaSource },
    { id: "recursive", label: "Recursive", schema: `${schemaSource}\n// recursive` },
  ],
};

export const bootstrap: Bootstrap = {
  status: "ready",
  seed: {
    status: "ready",
    serversAdded: 0,
    serversCompleted: 0,
    serversTarget: 0,
    totalServers: 48,
  },
  totals: { accounts: 4, servers: 48, users: 12 },
  schema,
  quickSubjects: [
    { id: "super-user", label: "Super user" },
    { id: "user-1", label: "User 1" },
  ],
  pageSizeOptions: [10, 20, 50, 100, 250, 500, 1000],
  defaultPageSize: 20,
};

export const cacheSnapshot: CacheSnapshot = {
  provider: { entries: 2, hits: 8 },
  operations: { "lookup-resources": { count: 3 } },
  capturedAt: "2026-08-08T10:00:00Z",
};

export function success<T>(data: T, revision = "d100.c0"): ApiSuccess<T> {
  return { data, meta: { revision, requestId: "test-request" } };
}

export function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "content-type": "application/json" },
  });
}

export function failure(code: string, message: string, status: number): Response {
  return jsonResponse({ error: { code, message } }, status);
}
