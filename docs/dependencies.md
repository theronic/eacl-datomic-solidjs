# Resolved dependency manifest

Captured on 2026-08-12 with Java 26.0.2 and Clojure CLI 1.12.5.1645.
`docs/dependency-tree.txt` is the complete output of `clojure -Stree` from
`server/`.

## EACL snapshot provenance

- Requested: `dev.eacl/eacl-datahike:8.0.0-SNAPSHOT`
- The exact snapshot is no longer transcribed here by hand. `server/build.clj`
  derives it from the dependency basis at `npm run build` time and embeds it
  in the uberjar at `META-INF/eacl-datahike-demo/build.edn`: the timestamped
  Clojars version (proved by matching the resolved jar's content against the
  timestamped download beside it in the Maven cache), the EACL commit stamped
  into the jar's POM `<scm><tag>` by the release workflow, and the SHA-256 of
  both the adapter and core jars, plus the demo's own Git commit, branch, and
  dirty flag.
- The running service reports the same record under `build` in `/api/health`
  and `/api/bootstrap`, and the explorer footer renders it with links. A
  source checkout (`npm run dev:server`) derives the EACL identity from the
  classpath instead and labels the demo side `development`.
- Print the record for the current checkout without building:
  `cd server && clojure -T:build provenance`.
- A snapshot that no Clojars download matches (for example one installed with
  `clojure -T:build install` from an EACL checkout) builds with a warning and is
  shown as `unpublished`; clear `~/.m2/repository/dev/eacl` first to deploy
  the CI-published artifact.

The first Clojars-resolved artifact was `8.0.0-20260814.204412-4` from source
commit `6cce96f15164fe42d1e2b55e58e32c307d5d0942` (adapter JAR SHA-256
`e7ce549d764f872e42efc3ea201bb0e18a0ad0b7e9a3998c496e4acd5aba0c79`, EACL core
JAR SHA-256 `d9793db2644ea123a6e28f335ec66003f5fd72494794ca8b23479dc4d7e5a1e7`);
the CI release guard publishes only after the formal verification workflow
passes on the exact source commit. The application contains no `:local/root`,
adapter override, or source preparation alias.

## Datahike and S3 backend

- `org.replikativ/datahike:0.8.1759`
- `org.replikativ/konserve-s3:0.1.37`
- `org.replikativ/datahike-lmdb:0.1.8` for the optional post-GC local
  LMDB/S3 serving tier; it resolves `konserve-lmdb:0.1.16` while the application
  retains the newer selected Konserve version below.
- Selected `org.replikativ/konserve:0.9.369`; Datahike's older `0.9.363`
  declaration is superseded by the direct S3 backend dependency.
- Konserve S3 JAR SHA-256: `b5ef10a34ca7c7235fa0d37e3f61107b2d075003a01ec4326e827d9fdc8ee808`
- Konserve S3 POM SHA-256: `b1e7ed60eddfc15b46aafdb7c24652ef0f49f1cc5397feb7f05fa7a6ec22ae29`

Compatibility is accepted only after the in-memory, file reconnect, and
disposable S3-compatible tests pass with this exact graph.
