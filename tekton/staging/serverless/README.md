# Serverless formal trigger activation

These two formal GitHub triggers are intentionally outside `tekton/v1` and its
kustomizations. Merge shared tasks/templates and devbuild routes first. Existing
source publishers stay active until native builds and devbuild E2E are verified.

After the private formal GAR exists and the five source cutover PRs are merged:

1. Create the initial annotated tags on the merged cutover commits: tidb-cse
   `v8.5.3-alpha.0`, pd-cse `v8.5.0-alpha.0`, tidb-operator-cse `v1.4.0-alpha.0`,
   cassandra-on-mysql `v0.1.0-alpha.0`, qdrant-on-tidb `v1.3.4-alpha.0`. Refuse to
   move a pre-existing tag to a different commit. Verify reachable semantic Git
   tags on every enabled maintenance branch; do not use a request version as a
   substitute for missing Git history.
   Divergent TiDB maintenance histories require independent reachable tags on
   their own merged cutover commits; the primary tag cannot be moved to cover
   them:

   | TiDB branch | Reserved cutover baseline |
   | --- | --- |
   | main-8.5-keyspace | v8.5.3-alpha.0 |
   | release-8.5-keyspace-20260206 | v8.5.3-alpha.20260206 |
   | release-8.5-keyspace-202603 | v8.5.3-alpha.202603 |
   | release-8.5-keyspace-202604 | v8.5.3-alpha.202604 |
   | release-8.5-keyspace-202605 | v8.5.3-alpha.202605 |
   | release-8.5-keyspace-202607 | v8.5.3-alpha.202607 |

   Merge the publisher-removal cutover for **each** enabled branch before
   activating its allowlist entry. Otherwise its old Actions continue to publish
   alongside Tekton. Keep a line disabled until its cutover, baseline and native
   validation are ready. The repository's initial primary baseline stays as
   specified above; these additional prereleases only establish branch ancestry.
2. Confirm each repository's GitHub push/create webhook reaches the existing
   GitHub EventListener, including the route's `short-ref` overlay. The explicit
   allowlist covers main-8.5-keyspace and the enumerated current 8.5 release lines,
   release-8.5-keyspace for PD, serverless-on-release-1.4 for Operator, Cassandra
   master and Qdrant-on-TiDB main. Future lines require a reviewed allowlist edit.
3. Copy these two YAML files to `tekton/v1/triggers/triggers/env-gcp/tidbcloud/serverless/`,
   regenerate `.ci/update-tekton-kustomizations.sh`, review the activation diff and
   merge it through the normal CI/GitOps process. Do not activate a second cluster
   for the same real GitHub events. Devbuild routes are independent in env-gcp and
   env-prod2 and always write the existing dev GAR.
4. Create/replay a version-tag event after activation if its original creation
   preceded listener enablement. Formal runs instantiate exactly amd64+arm64 and
   store packages/images under `.../serverless/tidbcloud/<product>/...`. Verify the
   full manifest and provenance before controller dev sync, then prod sync.

The single-platform template disables binary/image delivery and requires package,
checksum and image results. It mounts runtime GitHub credentials for compilation;
checkout uses github-bot. Product identity, not fork repo basename, chooses the
artifacts component. All tasks in a run share `artifacts-revision` (use a full
commit SHA when pinning); `versioning-strategy-url` is overridable for isolated PR
validation. Neither receives the API's compatibility request version.

## Validation records

Run `go test ./...` in `tools/serverless-trigger-tests` and
`deno test scripts/flow/build/versioning-strategy.test.ts`. Tests evaluate real CEL
filters/overlays, forks, legacy events/products, platform restrictions, generic
route exclusion, template run counts and declared task parameters. Use Tekton
server dry-run to validate admission, then native builds with pinned config/source
revisions. Before remote baseline creation, isolated validation may seed the
prescribed tag **only in its local checkout**; mark those runs validation-only and
publish to dev. Such runs do not establish production baselines or authorize
formal distribution. Record failed attempts and their corrected reruns.

Do not alter regional native Qdrant, TiKV or TiFlash routes. Preserve old artifacts
for rollback and do not automatically deploy business workloads.
