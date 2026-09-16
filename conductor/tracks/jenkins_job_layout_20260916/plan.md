# Implementation Plan: Co-locate Jenkins Job Artifacts in One Folder per Job

## Phase 1: Design and Conventions

- [ ] Task: Inventory the current layout and reference patterns
    - [ ] Enumerate every file under `jobs/` and `pipelines/` and count jobs, pipelines, pod templates
    - [ ] Classify job forms: flat (dsl+pipeline+pod), no-pod, existing nested directories, non-standard outliers
    - [ ] Record all reference patterns that must be rewritten (`scriptPath`, `POD_TEMPLATE_FILE`) and all scanners (`find pipelines ...`, verification scripts)
- [ ] Task: Author the design document under `docs/designs/`
    - [ ] Document the target `jobs/<org>/<repo>/<branch>/<job>/{dsl.groovy,Jenkinsfile,pod.yaml}` layout and naming rules
    - [ ] Document edge cases (optional pod.yaml, nested job dirs, non-standard jobs) and their handling
    - [ ] Document migration phases (dry-run -> apply -> verify -> cleanup), back-compat symlink strategy, cleanup gate, risks and rollback
- [ ] Task: Draft convention updates (content only)
    - [ ] Draft `AGENTS.md` structure/naming section changes
    - [ ] Draft `conductor/code_styleguides/` changes for the new file names
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Design and Conventions' (Protocol in workflow.md)

## Phase 2: Reference-Integrity Checker

- [ ] Task: Write failing tests for the checker (Red)
    - [ ] Create fixtures: valid refs, dangling `scriptPath`, dangling `POD_TEMPLATE_FILE`, orphaned artifact, job without pod.yaml
    - [ ] Add `.ci/test-check-jenkins-job-references.sh` that runs the checker against fixtures and asserts exit codes/messages
    - [ ] Run the test and confirm it fails because the checker does not exist yet
- [ ] Task: Implement `.ci/check-jenkins-job-references.sh` (Green)
    - [ ] Scan job folders and resolve every `scriptPath` and `POD_TEMPLATE_FILE` target
    - [ ] Fail with actionable output on missing targets or orphaned artifacts
    - [ ] Run the fixture test and confirm all cases pass
    - [ ] Run against the current repo and record the baseline result
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Reference-Integrity Checker' (Protocol in workflow.md)

## Phase 3: Migration Tool

- [ ] Task: Write failing tests for the tool (Red)
    - [ ] Create a sandbox fixture repo under `tests/fixtures/` mirroring `jobs/` + `pipelines/` samples
    - [ ] Add a test script asserting planned moves/renames, reference rewrites, symlink creation, idempotency and `--cleanup` guarding
    - [ ] Run the test and confirm it fails because the tool does not exist yet
- [ ] Task: Implement `scripts/migrate-jenkins-jobs.sh` (Green)
    - [ ] Implement job discovery/enumeration and classification
    - [ ] Implement `--dry-run` (default) report of planned moves and every rewritten reference
    - [ ] Implement `--apply`: create folders, move/rename files, rewrite refs, create back-compat symlinks
    - [ ] Implement `--cleanup`: remove `pipelines/` tree and symlinks only when the integrity checker is clean
    - [ ] Ensure idempotency and re-run safety
    - [ ] Run the fixture test and confirm all cases pass
    - [ ] Run `--dry-run` against the real repo and attach the report to the phase summary
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Migration Tool' (Protocol in workflow.md)

## Phase 4: Pilot Migration and Validation

- [ ] Task: Update `.ci/verify-jenkins-pipelines.sh` for the new layout
    - [ ] Repoint discovery from `pipelines/**/*.groovy` to `jobs/**/Jenkinsfile`
    - [ ] Confirm the script still validates a sample pipeline file
- [ ] Task: Migrate a pilot slice and validate
    - [ ] Select the pilot slice (e.g. `tikv/pd/latest` integration jobs) and run the tool
    - [ ] Run `.ci/check-jenkins-job-references.sh` and confirm zero dangling references
    - [ ] Run `.ci/verify-jenkins-pipelines.sh` on the migrated pilot and confirm syntax validation passes
- [ ] Task: Staging replay and rollback validation
    - [ ] Replay a migrated pilot job with `.ci/replay-jenkins-build.sh` and confirm success
    - [ ] Confirm old paths still resolve via the back-compat symlinks
    - [ ] Document and exercise the rollback procedure
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Pilot Migration and Validation' (Protocol in workflow.md)

## Phase 5: Documentation and Rollout Plan

- [ ] Task: Update documentation and conventions
    - [ ] Update `AGENTS.md` repository structure and file-naming conventions
    - [ ] Add/refresh a guide under `docs/guides/` describing the layout and migration workflow
    - [ ] Update `conductor/code_styleguides/` for the new names
- [ ] Task: Write the follow-up full-migration runbook and wire in checks
    - [ ] Document the full-repo runbook (dry-run -> apply -> verify -> cleanup)
    - [ ] Add the reference-integrity checker to pre-commit or a CI verification job
    - [ ] Note the follow-up full-migration track in the design doc
- [ ] Task: Conductor - User Manual Verification 'Phase 5: Documentation and Rollout Plan' (Protocol in workflow.md)
