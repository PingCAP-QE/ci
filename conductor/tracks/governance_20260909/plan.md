# Implementation Plan: Establish Conductor-based CI/CD Governance

> Workflow: see `conductor/workflow.md` (per-phase commits, Git-note summaries, >80% coverage for code modules, Phase Completion Verification and Checkpointing Protocol). Task status markers: `[ ]` pending, `[~]` in progress, `[x]` complete.

## Phase 1: Establish Conductor Baseline [checkpoint: ba5ea45]

- [x] Task: Review conductor context documents for internal consistency
    - [x] Cross-check `product.md`, `product-guidelines.md`, `tech-stack.md`, and `workflow.md` for contradictions
    - [x] Fix any inconsistencies found
- [x] Task: Wire repository development commands into the workflow
    - [x] Document `.ci/verify-jenkins-pipelines.sh` under Development Commands
    - [x] Document `.ci/update-prow-job-kustomization.sh` and `.ci/update-tekton-kustomizations.sh`
    - [x] Document pre-commit usage and `go test`/`deno test` entry points for Go/Deno tools
- [x] Task: Verify the baseline is usable
    - [x] Confirm `conductor/index.md` links resolve to all context files
    - [x] Confirm `conductor/tracks.md` and the track directory follow the registry format
- [ ] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md)

## Phase 2: Define Job/Pipeline Change Governance [checkpoint: af35967]

- [x] Task: Document the job-change lifecycle in the repo docs
    - [x] Author lifecycle: propose → plan → staging test → promote, aligned with `docs/contributing.md`
    - [x] Document the three-layer consistency rule (prow-jobs/ trigger + jobs/ DSL + pipelines/ implementation)
    - [x] Record where each new job change must update the plan and attach verification evidence
- [x] Task: Define a consistency checklist for job/pipeline reviews
    - [x] Checklist item: naming conventions per branch and job type
    - [x] Checklist item: pod templates and image tag variants referenced correctly
    - [x] Checklist item: OWNERS/sig approval routing
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)

## Phase 3: Define Branch Support & EOL Governance [checkpoint: 197c20f]

- [x] Task: Document the branch support matrix process
    - [x] Describe adding a new `release-X.Y` across `prow-jobs/`, `jobs/`, and `pipelines/`
    - [x] Describe removing an EOL `release-X.Y` across all three layers plus Tekton resources
- [x] Task: Document hotfix and EOL control hooks
    - [x] Reference hotfix branch rules and merge guards
    - [x] Reference maintained-GitHub-label removal and affects-X.Y label denial for EOL versions
- [x] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md)

## Phase 4: Validate Governance End to End [checkpoint: bd33a72]

- [x] Task: Execute repository verification gates
    - [x] Run `pre-commit run --all-files` and fix findings
    - [x] Run `.ci/verify-jenkins-pipelines.sh`
- [x] Task: Open and complete a small real maintenance track
    - [x] Select a low-risk job/pipeline or branch-sync change as the first governed track
    - [x] Implement the change through the governance lifecycle and close the track
- [ ] Task: Conductor - User Manual Verification 'Phase 4' (Protocol in workflow.md)
