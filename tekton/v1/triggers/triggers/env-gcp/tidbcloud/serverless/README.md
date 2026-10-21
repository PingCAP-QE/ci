# Serverless formal trigger activation

This directory is the single source of the formal GitHub push/tag routes. Its
kustomization entries activate both routes in the GCP EventListener. Merge the
activation PR only after the shared build resources are deployed and the source
cutovers, Git baselines and native build verification below are complete.

1. Remove the old image publishers on **every** branch in the allowlist before
   enabling its route. Keep any unprepared branch out of the allowlist to avoid
   duplicate publication. Confirm each repository's push/create webhook reaches
   the existing GitHub EventListener, including its `short-ref` overlay.
2. Create annotated baseline tags on the merged cutover commits. Never move an
   existing tag. Each enabled branch must have a reachable baseline:

   | Source / branch | Reserved cutover baseline |
   | --- | --- |
   | tidb-cse / main-8.5-keyspace | v8.5.3-alpha.0 |
   | tidb-cse / release-8.5-keyspace-20260206 | v8.5.3-alpha.20260206 |
   | tidb-cse / release-8.5-keyspace-202603 | v8.5.3-alpha.202603 |
   | tidb-cse / release-8.5-keyspace-202604 | v8.5.3-alpha.202604 |
   | tidb-cse / release-8.5-keyspace-202605 | v8.5.3-alpha.202605 |
   | tidb-cse / release-8.5-keyspace-202607 | v8.5.3-alpha.202607 |
   | pd-cse / release-8.5-keyspace | v8.5.0-alpha.0 |
   | tidb-operator-cse / serverless-on-release-1.4 | v1.4.0-alpha.0 |
   | cassandra-on-mysql / master | v0.1.0-alpha.0 |
   | qdrant-on-tidb / main | v1.3.4-alpha.0 |

   Divergent TiDB histories need independent reachable tags; the primary tag
   cannot be moved to cover them. Future release lines need a reviewed allowlist
   change. Request metadata does not substitute for Git history.
3. Validate the formal routes with the Deno tests documented in `tekton/README.md`
   and Tekton server dry-run. Verify native amd64/arm64 builds for every enabled
   component and branch. Isolated builds seeded with local-only tags do not
   establish production baselines. Record failed attempts and corrected reruns.
4. Merge the activation PR through the normal CI/GitOps process. Do not enable a
   second cluster for the same real GitHub events. Replay baseline tag events
   whose original creation preceded activation.
5. Verify the resulting packages, checksums, full image manifests and provenance
   under `us-docker.pkg.dev/pingcap-testing-account/serverless/tidbcloud/<product>/`.
   Each formal event creates exactly two Linux runs. Validate controller dev
   distribution before switching the production sources.

Serverless uses `git describe --tags --always --dirty` directly without branch
classification or inferred `pre` versions. The shared `versioning-strategy.ts`
file and its legacy invocation remain unchanged. Devbuild routes are managed
separately and do not distribute artifacts to environments.

Regional native Qdrant, TiKV and TiFlash remain unchanged. Preserve historical
artifacts for rollback; this activation does not deploy business workloads.
