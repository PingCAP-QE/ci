# Specification: Co-locate Jenkins Job Artifacts in One Folder per Job

## Overview

Today a single Jenkins job is spread across two directory trees:

- `jobs/<org>/<repo>/<branch>/<job>.groovy` — Jenkins Job DSL (`pipelineJob`) with `scriptPath("pipelines/<org>/<repo>/<branch>/<job>.groovy")`.
- `pipelines/<org>/<repo>/<branch>/<job>.groovy` — declarative pipeline with `POD_TEMPLATE_FILE = 'pipelines/<org>/<repo>/<branch>/pod-<job>.yaml'`.
- `pipelines/<org>/<repo>/<branch>/pod-<job>.yaml` — Kubernetes pod template.

Changing one job requires locating three files in two trees, and the DSL and pipeline files share the same `<job>.groovy` name, so they cannot simply be dropped into one folder.

Current scale: ~450 Job DSL files, ~374 pipeline scripts, ~368 pod templates. `.ci/verify-jenkins-pipelines.sh` scans the `pipelines/` tree.

## Goals

1. One folder per job that contains every artifact needed to understand and change that job.
2. Predictable, collision-free file names inside the folder.
3. Machine-checkable references: no dangling `scriptPath` / `POD_TEMPLATE_FILE`.
4. A safe, reversible migration path with back-compat and a cleanup gate.

## Target Layout

`jobs/<org>/<repo>/<branch>/<job>/`

| File | Purpose | Formerly |
|---|---|---|
| `dsl.groovy` | Jenkins Job DSL (`pipelineJob`) | `jobs/.../<job>.groovy` |
| `Jenkinsfile` | Declarative pipeline | `pipelines/.../<job>.groovy` |
| `pod.yaml` | Kubernetes pod template (OPTIONAL) | `pipelines/.../pod-<job>.yaml` |

The `pipelines/` tree is retired after migration. `pod.yaml` is omitted for jobs that define no pod template.

## Functional Requirements

- **FR1 — Job folder:** Every job is a folder `jobs/<org>/<repo>/<branch>/<job>/` containing `dsl.groovy` and `Jenkinsfile`; `pod.yaml` is optional.
- **FR2 — DSL reference:** In `dsl.groovy`, `scriptPath` points at the sibling `Jenkinsfile` using a repo-root-relative path, e.g. `pipelines/...` becomes `jobs/<org>/<repo>/<branch>/<job>/Jenkinsfile`.
- **FR3 — Pod reference:** In `Jenkinsfile`, `POD_TEMPLATE_FILE` (when present) points at `jobs/<org>/<repo>/<branch>/<job>/pod.yaml`; for jobs with no pod template the constant is removed and the default pod path is handled by the pipeline library.
- **FR4 — Existing directory-form entries:** Directories that already group pipeline files for one job (e.g. `pipelines/tikv/pd/latest/pull_integration_realcluster_test_next_gen/`) are treated as job folders; their inner pipeline files are co-located with a `dsl.groovy` in the same folder.
- **FR5 — Migration tool (bash):** `scripts/migrate-jenkins-jobs.sh` supports `--dry-run` (default) and `--apply`, is idempotent, and per job:
  - creates the job folder,
  - moves/renames `jobs/.../<job>.groovy` -> `dsl.groovy`, `pipelines/.../<job>.groovy` -> `Jenkinsfile`, `pipelines/.../pod-<job>.yaml` -> `pod.yaml`,
  - rewrites `scriptPath` and `POD_TEMPLATE_FILE` references,
  - creates back-compat symlinks from the old paths to the new paths,
  - emits a per-job summary report and a list of every rewritten reference.
- **FR6 — Reference-integrity checker (bash):** `.ci/check-jenkins-job-references.sh` scans all job folders, resolves every `scriptPath` and `POD_TEMPLATE_FILE`, and fails (non-zero) when a target is missing or an unreferenced/orphaned artifact is found.
- **FR7 — Verification script update:** `.ci/verify-jenkins-pipelines.sh` is repointed to walk the new layout (`jobs/**/Jenkinsfile`) instead of `pipelines/**/*.groovy`.
- **FR8 — Cleanup:** A documented cleanup step (`--cleanup`) removes the `pipelines/` tree and the back-compat symlinks only after the integrity checker, syntax validation, and staging replay pass.
- **FR9 — Documentation:** New design doc under `docs/designs/`; updates to `AGENTS.md`, `docs/guides/`, and `conductor/code_styleguides/` to describe the new layout and naming.

## Non-Functional Requirements

- **Safe/reversible:** dry-run by default; no deletion until verification passes; symlinks keep old paths resolvable during transition.
- **Reviewable:** per-job report so a human can audit a migration before cleanup.
- **Idempotent:** re-running the tool on a migrated repo is a no-op.
- **Compliant:** passes pre-commit (EOF/whitespace), gitleaks, and Conventional Commits.

## Acceptance Criteria

- [ ] Design doc describes the target layout, naming rules, edge cases, migration phases, risks, and rollback.
- [ ] Migration tool `--dry-run` prints planned moves and all reference rewrites without modifying files.
- [ ] Reference-integrity checker passes on a migrated pilot and fails on a deliberately dangling reference (negative test).
- [ ] `.ci/verify-jenkins-pipelines.sh` validates pipelines from the new layout.
- [ ] Pilot: one job slice migrated; integrity checker clean; Jenkins pipeline syntax validation passes; old paths resolve via symlink; staging replay succeeds.
- [ ] Docs and conventions updated to the new layout.

## Edge Cases

- Jobs without a pod template: `pod.yaml` is optional and `POD_TEMPLATE_FILE` is removed from the pipeline.
- Existing nested job directories: migrated as job folders (FR4).
- Non-standard jobs (e.g. `ti-community-infra/test-prod/prow_debug.groovy`): the tool must report them for manual handling rather than silently skipping.

## Assumptions

- Jenkins `cpsScm.scriptPath` is resolved relative to the repository root, so it can safely point into `jobs/`.
- `pod.yaml` is resolved relative to the workspace as used by `pod_label.withCiLabels`.
- Jenkins jobs track `main`, so merged migrations take effect on the next build.

## Out of Scope

- Executing the full migration of all ~450 jobs (follow-up implementation track).
- Changing Jenkins job names, Prow triggers, pipeline logic, or runtime behavior.
- Restructuring Tekton resources or Prow job YAML.

## Risks

- **Stale `scriptPath` while Jenkins re-indexes:** mitigated by back-compat symlinks retained for one release.
- **Merge conflicts with in-flight PRs** touching moved files: mitigated by keeping this track to design + tooling, with migration executed incrementally later.
- **Symlink handling differences** across tooling: validated by staging replay before cleanup.

## Success Metrics

- One job = one folder for the pilot slice; zero dangling references detected.
- Migration tool can plan the full repo (`--dry-run`) with no unrecognized files.
- Follow-up full migration is reduced to running the tool + cleanup.
