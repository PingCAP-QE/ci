# Specification: Establish Conductor-based CI/CD Governance

## Goal

Stand up a repeatable, Conductor-driven process for maintaining the PingCAP-QE/ci monorepo: every CI job/pipeline change and branch-lifecycle action is executed from a tracked plan, verified against the repo's own conventions, and recorded with audit trail (plan.md checkpoints + Git notes).

## Background

PingCAP-QE/ci is an as-code monorepo managing CI/CD for the PingCAP/TiDB/TiKV product line. Changes span three coupled layers — Prow triggers (`prow-jobs/`), Jenkins Job DSL (`jobs/`), and Jenkins pipelines (`pipelines/`) — plus Tekton CD resources, shared libraries, and per-org/per-repo/per-branch directory conventions. Product priorities: manage all CI/CD configs, keep CI reliable, and maintain branches in sync with the supported branch matrix.

## Scope

1. A Conductor project baseline that anchors future work to the docs and conventions defined during setup.
2. A governed lifecycle for CI job/pipeline changes (plan → implement → verify → checkpoint).
3. A governed process for branch support and end-of-life maintenance across repos.
4. Explicit verification and tooling hooks (repo scripts, pre-commit) wired into the workflow.

## Out of Scope

- Modifying individual production job definitions.
- Running actual release/delivery operations.
- Rewriting existing pipeline implementations.

## Acceptance Criteria

- Conductor setup files are committed and referenceable from the repo index.
- A template lifecycle exists for turning a job/pipeline change request into a tracked Conductor plan with phases and checkpoints.
- A documented branch-support matrix process exists for adding/removing `release-X.Y` configs across `prow-jobs/`, `jobs/`, and `pipelines/`.
- Repository verification commands are documented in the workflow's Development Commands section.
- The first real maintenance task can be opened as a Conductor track and implemented end to end.
