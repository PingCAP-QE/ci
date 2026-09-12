# Implementation Plan: Establish Conductor-based CI/CD Governance

> Workflow: see `conductor/workflow.md` (per-phase commits, Git-note summaries, >80% coverage for code modules, Phase Completion Verification and Checkpointing Protocol). Task status markers: `[ ]` pending, `[~]` in progress, `[x]` complete.

## Phase 1: Establish Conductor Baseline

- [ ] Task: Review conductor context documents for internal consistency
    - [ ] Cross-check `product.md`, `product-guidelines.md`, `tech-stack.md`, and `workflow.md` for contradictions
    - [ ] Fix any inconsistencies found
- [ ] Task: Wire repository development commands into the workflow
    - [ ] Document `.ci/verify-jenkins-pipelines.sh` under Development Commands
    - [ ] Document `.ci/update-prow-job-kustomization.sh` and `.ci/update-tekton-kustomizations.sh`
    - [ ] Document pre-commit usage and `go test`/`deno test` entry points for Go/Deno tools
- [ ] Task: Verify the baseline is usable
    - [ ] Confirm `conductor/index.md` links resolve to all context files
    - [ ] Confirm `conductor/tracks.md` and the track directory follow the registry format
- [ ] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md)

## Phase 2: Define Job/Pipeline Change Governance

- [ ] Task: Document the job-change lifecycle in the repo docs
    - [ ] Author lifecycle: propose → plan → staging test → promote, aligned with `docs/contributing.md`
    - [ ] Document the three-layer consistency rule (prow-jobs/ trigger + jobs/ DSL + pipelines/ implementation)
    - [ ] Record where each new job change must update the plan and attach verification evidence
- [ ] Task: Define a consistency checklist for job/pipeline reviews
    - [ ] Checklist item: naming conventions per branch and job type
    - [ ] Checklist item: pod templates and image tag variants referenced correctly
    - [ ] Checklist item: OWNERS/sig approval routing
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)

## Phase 3: Define Branch Support & EOL Governance

- [ ] Task: Document the branch support matrix process
    - [ ] Describe adding a new `release-X.Y` across `prow-jobs/`, `jobs/`, and `pipelines/`
    - [ ] Describe removing an EOL `release-X.Y` across all three layers plus Tekton resources
- [ ] Task: Document hotfix and EOL control hooks
    - [ ] Reference hotfix branch rules and merge guards
    - [ ] Reference maintained-GitHub-label removal and affects-X.Y label denial for EOL versions
- [ ] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md)

## Phase 4: Validate Governance End to End

- [ ] Task: Execute repository verification gates
    - [ ] Run `pre-commit run --all-files` and fix findings
    - [ ] Run `.ci/verify-jenkins-pipelines.sh`
- [ ] Task: Open and complete a small real maintenance track
    - [ ] Select a low-risk job/pipeline or branch-sync change as the first governed track
    - [ ] Implement the change through the governance lifecycle and close the track
- [ ] Task: Conductor - User Manual Verification 'Phase 4' (Protocol in workflow.md)
