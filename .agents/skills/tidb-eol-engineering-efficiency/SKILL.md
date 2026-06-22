---
name: tidb-eol-engineering-efficiency
description: "Handle TiDB Engineering Efficiency EOL work for a TiDB minor version across PingCAP-QE/ci and ti-community-infra/configs. Here TiDB means the whole customer-facing TiDB product line, not only pingcap/tidb. Use when retiring release-X.Y support across product components, including patch and hotfix SDLC for vX.Y.Z: delete CI jobs and resources, remove periodic or cron jobs, remove release-X.Y, release-X.Y.Z, hotfix branch, and vX.Y.* ranges, deny affects-X.Y labeling, block merges to release-X.Y* branches, and remove maintained GitHub labels for that EOL version."
---

# TiDB EOL Engineering Efficiency

## Scope

This skill is for retiring one TiDB minor version such as `8.5`.
Here, `TiDB` means the whole product service composed from multiple component repos, not just `pingcap/tidb`.

Default repos:
- current repo: `PingCAP-QE/ci`
- sibling repo: `../../ti-community-infra/configs`

Default product scope in `PingCAP-QE/ci`:
- core database repos: `pingcap/tidb`, `tikv/tikv`, `tikv/pd`
- data service components: `pingcap/tiflash`, `pingcap/tiflow`, `pingcap/ticdc`, `pingcap/tidb-binlog`
- test and release-adjacent repos: `PingCAP-QE/tidb-test`, `pingcap/tidb-tools`, `pingcap/monitoring`
- docs and delivery repos when they still carry EOL release routing: `pingcap/docs`, `pingcap/docs-cn`, related postsubmit sync jobs
- internal mirrors or release-flow repos when present: `pingcap-inc/tidb`, `pingcap-inc/tiflow`, similar `pingcap-inc/*` entries

Default version forms:
- branch: `release-X.Y`
- patch branch: `release-X.Y.Z`
- patched branch prefix: `release-X.Y.`
- dated hotfix branch: `release-X.Y-YYYYMMDD-vX.Y.Z`
- feature branch prefix: `feature/release-X.Y-`
- patch feature branch prefix: `feature/release-X.Y.Z-` and `feature_release-X.Y.Z-`
- tag/version: `vX.Y.*`
- labels: `affects-X.Y`, `may-affects-X.Y`, `needs-cherry-pick-release-X.Y`, `type/cherry-pick-for-release-X.Y`

## Workflow

1. Resolve the target version once.
   - Keep `minor=X.Y`, `release_branch=release-X.Y`, and `minor_compact=XY` for names like `v85`.
   - Treat the whole `vX.Y.*` family as retired, including patch tags and customer hotfix branches.
2. Start with discovery.
   - Run `.agents/skills/tidb-eol-engineering-efficiency/scripts/find_tidb_eol_candidates.sh X.Y`.
   - Review the candidate list before deleting anything.
3. Clean `PingCAP-QE/ci`.
   - Search all release-managed TiDB product components, not only `pingcap/tidb`.
   - Remove branch-dedicated Prow jobs for the EOL branch across affected component repos.
   - Remove matching Jenkins job DSL and pipeline resources for the EOL branch across affected component repos.
   - Remove periodic or cron Prow jobs tied to the EOL version across the product CI surface.
   - Remove version-specific branch and tag ranges that keep the EOL line active for any TiDB product component.
   - Remove patch and hotfix SDLC support for `vX.Y.Z`, including:
     - `release-X.Y.Z` patch branches,
     - `release-X.Y-YYYYMMDD-vX.Y.Z` hotfix branches,
     - `feature/release-X.Y.Z-*` and `feature_release-X.Y.Z-*`,
     - shared regex exclusions or special routing that preserve customer hotfix flows.
   - Run `bash .ci/update-prow-job-kustomization.sh` after Prow file removals.
4. Clean `ti-community-infra/configs`.
   - Remove EOL version labels from `prow/config/external_plugins_config.yaml` for all affected TiDB product repos so users can no longer add them.
   - Remove maintained label definitions for the EOL version from `prow/config/labels.yaml`.
   - Regenerate `prow/config/labels.md`.
   - Remove `release-X.Y`, `release-X.Y.`, and any version-specific patch or hotfix branch entries from the Prow config blocks that still allow merging to that branch family across the product repos.
5. Validate both repos.
   - Re-run targeted `rg` searches and confirm only intentional historical references remain.
   - Report deleted files, edited policy blocks, and any ambiguous leftovers.

## Guardrails

- Delete only TiDB product resources dedicated to the EOL version. Do not touch trunk or still-supported minors.
- If a file serves multiple versions, remove only the EOL entries instead of deleting the whole file.
- Some repos are part of the product scope only indirectly, such as docs sync, monitoring, or QE test repos. Remove EOL routing only when it exists in active release automation.
- Shared library logic and regexes may distinguish between release branches, patch branches, and dated hotfix branches. Remove the EOL-specific support without breaking still-supported minors.
- In `ti-community-infra/configs/prow/config/config.yaml`, preserve broader branch patterns that still belong to maintained lines.
- After label changes in `ti-community-infra/configs`, regenerate and verify the derived docs.
- If the sibling `ti-community-infra/configs` checkout is missing, locate the local checkout first instead of guessing paths.

## Read Next

- For `PingCAP-QE/ci` search surfaces and validation, read [references/ci-repo.md](references/ci-repo.md).
- For `ti-community-infra/configs` edit points and validation, read [references/ti-community-infra-configs.md](references/ti-community-infra-configs.md).
