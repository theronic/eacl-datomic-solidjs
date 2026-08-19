import type {
  ApiSuccess,
  Bootstrap,
  BuildInfo,
  CacheSnapshot,
  SchemaInfo,
} from "../src/types";

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
    { id: "default", label: "Non-recursive", schema: schemaSource },
    { id: "recursive", label: "Recursive", schema: `${schemaSource}\n// recursive` },
  ],
};

export const build: BuildInfo = {
  application: "eacl-datahike-demo",
  development: false,
  builtAt: "2026-08-18T22:01:47.120Z",
  source: {
    repository: "https://github.com/theronic/eacl-datomic-solidjs",
    commit: "06d8141a0cfebbd3b423cd719f9f05eb94ca50aa",
    ref: "agent/port-to-datahike-demo",
    dirty: false,
    committedAt: "2026-08-14T22:50:39+02:00",
  },
  eacl: {
    repository: "https://github.com/theronic/eacl",
    requestedVersion: "8.0.0-SNAPSHOT",
    adapter: {
      lib: "dev.eacl/eacl-datahike",
      version: "8.0.0-SNAPSHOT",
      resolvedVersion: "8.0.0-20260818.233134-7",
      commit: "f4be377a139f9bc9dfcb9c40f91418bdbf3a4b3d",
      jarSha256: "fce16a8a5693c3d6b5417d3d44be804bd1c28e6e4f068f220e6fa791a3210856",
    },
    core: {
      lib: "dev.eacl/eacl",
      version: "8.0.0-SNAPSHOT",
      resolvedVersion: "8.0.0-20260818.233119-7",
      commit: "f4be377a139f9bc9dfcb9c40f91418bdbf3a4b3d",
      jarSha256: "5cfdcbd21e59d646b06e21c3f4f3c69198e4eadd7ca5dc875dc337183d8b1934",
    },
  },
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
  capabilities: {
    schemaWrite: true,
    seedWrite: true,
    cacheEvict: true,
  },
  build,
};

export const cacheSnapshot: CacheSnapshot = {
  provider: { entries: 2, hits: 8 },
  operations: { "lookup-resources": { count: 3 } },
  capturedAt: "2026-08-08T10:00:00Z",
};

export function success<T>(data: T, revision = "h100.c0"): ApiSuccess<T> {
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
