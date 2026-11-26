# Implementation plan

## Phase 1: isolated staging job and static verification
- [x] Add matching Prow, Jenkins DSL, pipeline and Pod definitions.
- [x] Document the manual command and known TiFlow compatibility blockers.
- [x] Run reference, YAML, Pod-policy and pipeline-model checks.
- [x] Smoke-test the pinned MySQL image and authentication parameters.
- [x] Open a PR with verification evidence and limitations:
      https://github.com/PingCAP-QE/ci/pull/5625

### Verification evidence
- Offline job contract checks passed (optional/manual status, references,
  unchanged baseline containers, pinned image, groups and cache behavior).
- Jenkins job reference check and Prow kustomization regeneration passed;
  the generated kustomization was unchanged.
- Pod structural, resource and privilege-policy checks passed with no new
  allowlist exceptions.
- The Jenkins pipeline-model validator reported `Jenkinsfile successfully validated.`
- The exact pinned image started with the Pod's arguments. Non-TLS TCP login
  succeeded with native authentication, port 3307, ROW binlog and GTID ON.
  `VERSION()` returned `8.4.13`; `VERSION_WITH_UPDATE()` returned `8.4.13.1`.
- Confirmed that `SHOW MASTER STATUS` and `RESET MASTER` fail with syntax error
  1064, while their replacement statements succeed. No full DM suite was run.

## Phase 2: operator verification after deployment
- [ ] Verify the new context is not explicitly required by branch protection
      or supplemental Tide rules.
- [ ] Run the staging job on a TiFlow PR; retain the build link and failures.
- [ ] Confirm the optional pending/failed context does not block merging.

No automatic or required-job promotion is included in this change.
