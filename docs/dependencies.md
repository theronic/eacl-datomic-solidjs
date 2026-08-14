# Resolved dependency manifest

Captured on 2026-08-12 with Java 26.0.2 and Clojure CLI 1.12.5.1645.
`docs/dependency-tree.txt` is the complete output of `clojure -Stree` from
`server/`.

## EACL stable-discovery snapshot

- Requested: `dev.eacl/eacl-datahike:8.0.0-SNAPSHOT`
- Resolved snapshot: `8.0.0-20260814.204412-4`, published by the CI Clojars
  release from branch `v8.0.0-SNAPSHOT` after Tests and Formal verification
  passed (PR 116 tracks the change)
- Source commit: `6cce96f15164fe42d1e2b55e58e32c307d5d0942`
- Adapter JAR SHA-256: `e7ce549d764f872e42efc3ea201bb0e18a0ad0b7e9a3998c496e4acd5aba0c79`
- Adapter POM SHA-256: `f8101232ea721d5b1f1b69041fa6da29332e3cfa107bd41bc86105bd6b1d7cde`
- EACL core JAR SHA-256: `d9793db2644ea123a6e28f335ec66003f5fd72494794ca8b23479dc4d7e5a1e7`
- EACL core POM SHA-256: `4f9d05224475487381643ba9fdb299ae6413d440a7477daca2f5ce984ede29be`

This is the first artifact resolved from Clojars itself rather than a locally
installed pre-release build: the CI release guard publishes only after the
formal verification workflow passes on the exact source commit. The
application contains no `:local/root`, adapter override, or source preparation
alias; the deployable uberjar records the source commit and both exact JAR
checksums.

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
