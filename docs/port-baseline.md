# Datomic demo port baseline

Captured 2026-08-12 before Datahike-specific edits.

## Source state

- Source repository: `theronic/eacl-datomic-solidjs`
- Commit: `33fd1011050f4e089c6ee6022385fd6dad2aa272`
- Pre-existing source changes copied as content but left untouched at source:
  - modified `client/tests/setup.ts`
  - untracked `pnpm-lock.yaml`
- The new project has a separate `.git` directory and separately allocated files.

## HTTP parity checklist

| Method | Route |
| --- | --- |
| GET | `/api/health` |
| GET | `/api/bootstrap` |
| GET | `/api/subjects` |
| POST | `/api/eacl/lookup-resources` |
| POST | `/api/eacl/count-resources` |
| POST | `/api/eacl/lookup-subjects` |
| POST | `/api/eacl/read-relationships` |
| POST | `/api/eacl/check-permission` |
| GET, PUT | `/api/schema` |
| GET | `/api/cache` |
| POST | `/api/cache/evict` |
| GET, POST | `/api/seed` |

Unknown `/api/*` routes return the stable API error envelope. Non-API GET
requests use static-resource lookup with SPA fallback.

## Test parity checklist

Backend tests cover environment validation, JSON contracts, route/method
handling, request bounds, real EACL pagination/count/relationship/permission
operations, cache behavior, schema replacement, cursor mismatch, seed overlap,
and asynchronous seed availability.

Client tests cover API serialization/errors, preferences, resource trees,
subjects, schema, and cache panels. Playwright covers the integrated explorer.

## Source commands

- Client install: `npm run install:client`
- Local server: `npm run dev:server`
- Local nREPL: `npm run dev:repl`
- Client dev server: `npm run dev:client`
- Client lint/tests/build: `npm run verify`
- Browser suite: `npm run test:e2e`

The port removes all `prep:local-eacl`, `*:local-eacl`, Datomic transactor, and
local adapter checkout workflows. The published Clojars snapshot is the only
EACL dependency source.
