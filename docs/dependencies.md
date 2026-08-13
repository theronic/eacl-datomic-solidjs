# Resolved dependency manifest

Captured on 2026-08-12 with Java 26.0.2 and Clojure CLI 1.12.5.1645.
`docs/dependency-tree.txt` is the complete output of `clojure -Stree` from
`server/`.

## EACL PR 115 snapshot

- Requested: `dev.eacl/eacl-datahike:8.0.0-SNAPSHOT`
- Resolved snapshot: `8.0.0-SNAPSHOT`, installed from EACL PR 115
- Source commit: `142882c56e2e4f0c4e37a5740fd0f0db96d066e9`
- Adapter JAR SHA-256: `4ca345d6d23fd3e4779e63df791cd529feaf44cf09737f07f1fbc42d1c6be501`
- Adapter POM SHA-256: `cf93ccd3b90242cf19188c6f1e3d5cd291d92b26aa4cf8cf0b60e407a0e35520`
- EACL core JAR SHA-256: `6747516f56f6a867b9ac0140d2e0493d0fdedcff201a040c1b870ac3b4a2ab5b`
- EACL core POM SHA-256: `8aa7ea4c2ad83c61f8a9639e79adc24740b7bf268a06c010cb08eda89642e8fc`

The newest Clojars timestamp at build time (`8.0.0-20260812.135622-3`, source
commit `5f50aea220adab136496176ae3c8111684c3a9fb`) predates PR 115 and does not
contain cooperative cancellation. The two PR artifacts were therefore built
with the EACL release tasks and installed under the requested Maven snapshot
coordinate before resolving this application. The application itself still
contains no `:local/root`, adapter override, or source preparation alias; the
deployable uberjar records the PR commit and both exact JAR checksums. Rebuild
from a clean Maven cache only after PR 115 is published, or install that same
commit first and regenerate this manifest.

## Datahike and S3 backend

- `org.replikativ/datahike:0.8.1759`
- `org.replikativ/konserve-s3:0.1.37`
- Selected `org.replikativ/konserve:0.9.369`; Datahike's older `0.9.363`
  declaration is superseded by the direct S3 backend dependency.
- Konserve S3 JAR SHA-256: `b5ef10a34ca7c7235fa0d37e3f61107b2d075003a01ec4326e827d9fdc8ee808`
- Konserve S3 POM SHA-256: `b1e7ed60eddfc15b46aafdb7c24652ef0f49f1cc5397feb7f05fa7a6ec22ae29`

Compatibility is accepted only after the in-memory, file reconnect, and
disposable S3-compatible tests pass with this exact graph.
