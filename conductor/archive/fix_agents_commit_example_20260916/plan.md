# Implementation Plan: Fix the AGENTS.md commit-convention example

## Phase 1: Fix and verify [checkpoint: 06ce94c]

- [x] Task: Update the commit example in `AGENTS.md`
    - [x] Replace `ci(prow): add presubmit for tiflow lint` with the compliant example `feat(prow-jobs): add presubmit for tiflow lint`
    - [x] Confirm no other `ci(...)` example contradicts the documented rule
- [x] Task: Verify the change
    - [x] Run `pre-commit run --files AGENTS.md`
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md)
