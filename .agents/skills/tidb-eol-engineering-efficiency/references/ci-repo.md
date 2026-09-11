# `PingCAP-QE/ci` Reference

For this skill, `TiDB` means the whole customer-facing TiDB product line. Search across all release-managed component repos that participate in TiDB service delivery.

Also cover patch and hotfix SDLC removal for the retired minor:
- patch branch: `release-X.Y.Z`
- hotfix branch: `release-X.Y-YYYYMMDD-vX.Y.Z`
- patch feature branch: `feature/release-X.Y.Z-*` and `feature_release-X.Y.Z-*`
- patch tags: `vX.Y.Z`

## Main edit surfaces

- `prow-jobs/**`
  - Product-component Prow job YAMLs such as:
    - `prow-jobs/pingcap/tidb/`
    - `prow-jobs/tikv/tikv/`
    - `prow-jobs/tikv/pd/`
    - `prow-jobs/pingcap/tiflash/`
    - `prow-jobs/pingcap/tiflow/`
    - `prow-jobs/pingcap-qe/tidb-test/`
    - `prow-jobs/pingcap/monitoring/`
    - `prow-jobs/pingcap/docs/` and `prow-jobs/pingcap/docs-cn/` when release sync still targets the EOL line
    - `prow-jobs/pingcap-inc/*` mirrors when they are part of release automation
- `jobs/**/release-X.Y/`
  - Jenkins DSL for any product component still carrying the EOL release branch.
- `pipelines/**/release-X.Y/`
  - Pipeline Groovy files and pod templates for any product component still carrying the EOL release branch.
- `prow-jobs/pingcap-qe/ci/periodics.yaml`
  - Version-specific TiDB periodic jobs such as flaky-test reporters.
- `prow-jobs/**/periodics.yaml`
  - Search for any other TiDB product EOL periodic or cron-style Prow jobs.
- `prow-jobs/kustomization.yaml`
  - Generated after Prow file additions or removals.

## Discovery commands

Replace `X.Y` and `XY` with the target version.

```bash
rg -n "release-X\\.Y($|\\.)|release-X\\.Y-|feature[/_]release-X\\.Y([.-]|$)|vX\\.Y\\.[0-9]+|vXY" \
  prow-jobs \
  jobs \
  pipelines
```

```bash
find jobs -type d -path "*/release-X.Y" 2>/dev/null | sort
find jobs -type d \( -path "*/release-X.Y.*" -o -path "*/release-X.Y-*" \) 2>/dev/null | sort
find pipelines -type d -path "*/release-X.Y" 2>/dev/null | sort
find pipelines -type d \( -path "*/release-X.Y.*" -o -path "*/release-X.Y-*" \) 2>/dev/null | sort
```

## Edit rules

- Delete dedicated release-branch files and directories when they only serve `release-X.Y`.
- Delete dedicated patch-branch and hotfix-branch files and directories when they only serve the retired `vX.Y.*` family.
- Expect the EOL work to span multiple component repos in one change set.
- In shared YAMLs, remove only the EOL entries:
  - `branches`
  - `run_if_changed`
  - version-specific job names
  - `cron` or `interval` jobs for the EOL line
  - tag or branch regexes matching `release-X.Y`, `release-X.Y.Z`, dated hotfix branches, or `vX.Y.*`
- Also review shared postsubmit flows that still target `release-X.Y`, for example sync or backport jobs in `common-postsubmits.yaml` or docs sync jobs.
- Review hotfix-aware or patch-aware logic in shared libraries and pipelines. Existing patterns in this repo include:
  - `release-X.Y-YYYYMMDD-vX.Y.Z`
  - `feature/release-X.Y.Z-*`
  - `feature_release-X.Y.Z-*`
  - repo-specific special cases for hotfix support
- Review generated names carefully. Some periodics use compact forms like `v85`.
- Not every `vX.Y.*` hit should be deleted. Test assets or compatibility fixtures in still-maintained branches need manual review.

## Validation

```bash
bash .ci/update-prow-job-kustomization.sh
bash .ci/verify-jenkins-pipelines.sh
```

Then confirm no live config remains for the EOL line:

```bash
rg -n "release-X\\.Y($|\\.)|release-X\\.Y-|feature[/_]release-X\\.Y([.-]|$)|vX\\.Y\\.[0-9]+|vXY" \
  prow-jobs \
  jobs \
  pipelines
```

Historical comments or cross-version test fixtures may remain outside the active CI paths. Call those out instead of deleting blindly.
