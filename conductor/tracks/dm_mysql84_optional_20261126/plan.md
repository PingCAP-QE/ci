# Implementation plan

## Phase 1: isolated staging job and static verification
- [~] Add matching Prow, Jenkins DSL, pipeline and Pod definitions.
- [ ] Document the manual command and known TiFlow compatibility blockers.
- [ ] Run reference, YAML, Pod-policy and pipeline-model checks.
- [ ] Smoke-test the pinned MySQL image and authentication parameters.
- [ ] Open a PR with verification evidence and limitations.

## Phase 2: operator verification after deployment
- [ ] Verify the new context is not explicitly required by branch protection
      or supplemental Tide rules.
- [ ] Run the staging job on a TiFlow PR; retain the build link and failures.
- [ ] Confirm the optional pending/failed context does not block merging.

No automatic or required-job promotion is included in this change.
