# Implementation plan

## Phase 1: Prepare the migration

- [x] Compare the six job definitions with `pingcap/tidb/release-8.5`.
- [x] Route the remaining six jobs to Tencent through the existing shared label.
- [x] Align the BR Pod template with the upstream image, resources and setup.
- [x] Run static checks and verify the expanded Prow configuration.
- [x] Prepare draft-PR scope, validation evidence and rollback instructions.

## Phase 2: Validate before promotion

- [ ] Run the existing migration verifier / staging replay for the six jobs.
- [ ] Distinguish migration failures from known TiDB source/test failures.
- [ ] Confirm checkout, artifact downloads, mounted cache and BR runtime behavior.
- [ ] Record successful staging/replay links and obtain review before promotion.
- [ ] Obtain explicit user authorization before merging this specific PR.

## Evidence

- Baseline: CI main `7f16006f8`; upstream job folder
  `jenkins/jobs/pingcap/tidb/release-8.5/`.
- TiDB PR `pingcap-inc/tidb#22`, head `404f85f833`: the retry-flag startup
  failure is fixed. The current five Bazel check failures are Go 1.27
  formatting/compression-test gaps; BR records OOMKilled with a 12Gi limit.
- Controller 0 and the token broker already run the inc MySQL job successfully.
- Characterization assertions pass: all eight routes are controller 0, exactly
  six routes change, every other expanded job field is unchanged, the BR Pod
  exactly matches upstream, and all six Jenkinsfiles / five other Pods are unchanged.
- Reference, Pod structure/image, resource, privilege and storage-class checks pass.
- Prow kustomization regeneration has no diff; `kubectl kustomize prow-jobs` passes.
- Replay routing dry-run selects controller 0 without submitting a build.
- Pre-commit checks pass on all four changed files, including the secret scan.
- Production cutover is intentionally deferred; this track prepares a draft PR.
