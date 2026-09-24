# Job and Pipeline Change Governance

This guide defines how a CI job or pipeline change is proposed, planned, implemented,
verified and promoted in this repository. It complements
[Contributing](../contributing.md), which describes the staging-to-production mechanics.

## Why governance

CI job and pipeline files are production infrastructure for every PingCAP/TiDB/TiKV
repository. A single job is represented by a Prow trigger plus the coupled files in one
job folder under `jenkins/jobs/` (`dsl.groovy`, `Jenkinsfile`, optional `pod.yaml`), so
an unreviewed or out-of-sync edit can break PR feedback for a whole branch. This process
makes each change traceable from proposal to promotion and records the evidence used to
accept it.

## Change lifecycle

Every non-trivial job/pipeline change follows this lifecycle:

1. **Propose** — describe the change, its motivation and the affected repositories/branches.
2. **Plan** — open a Conductor track under `conductor/tracks/<track_id>/` with a
   `spec.md` (what and why) and a `plan.md` (phases and tasks). The plan is the source
   of truth for the work.
3. **Implement** — work through `plan.md` task by task, following `conductor/workflow.md`
   (per-phase checkpoint commits and Git-note summaries).
4. **Verify** — run the static validators and, for pipeline behavior, a staging/replay
   test. Keep the commands and results.
5. **Promote** — follow the staging-to-production flow in
   [Contributing](../contributing.md) and attach test evidence (build links, command
   output) to the promotion PR.

This lifecycle maps onto the pipeline workflow in [CI](./CI.md):

| Governance step | [CI guide](./CI.md) / [Contributing](../contributing.md) step |
|---|---|
| Propose + Plan | Identify the pipeline, describe the change, create the track |
| Implement | Copy to `/staging`, make changes, open the PR |
| Verify | Static validation + replay/staging test |
| Promote | PR that moves staging to production, with test links |

## Three-layer consistency rule

A Jenkins job is not a single file. Treat these artifacts as one unit and keep their
names and references consistent:

| Layer | Location | Key reference |
|---|---|---|
| Prow trigger | `prow-jobs/<org>/<repo>/<branch>-<jobtype>.yaml` | Prow job `name`, and `labels.master` for the Jenkins backend |
| Jenkins Job DSL | `jenkins/jobs/<org>/<repo>/<branch>/<job>/dsl.groovy` | `pipelineJob('<org>/<repo>/<job>')` and `scriptPath(...)` |
| Jenkins pipeline | `jenkins/jobs/<org>/<repo>/<branch>/<job>/Jenkinsfile` | `POD_TEMPLATE_FILE` |
| Pod template | `jenkins/jobs/<org>/<repo>/<branch>/<job>/pod.yaml` (or `pod-<purpose>.yaml`) | container images and resource requests |

Rules:

- The Prow job name, the `pipelineJob(...)` name, the DSL file name and the pipeline
  file name must all describe the same job.
- `scriptPath` must point at an existing pipeline file, and `POD_TEMPLATE_FILE` must
  point at an existing pod template.
- A change is not complete until every affected layer is updated together.

## Track bookkeeping and evidence

- Record progress in the track `plan.md`: mark a task `[~]` when starting and `[x]` when
  done.
- Attach verification evidence to the phase checkpoint Git note (see
  `conductor/workflow.md`), for example the exact validation commands and their results,
  and any staging/replay build URLs.
- When comparing build numbers or other non-GitHub numbers in PR text, wrap them in
  backticks or use full URLs so GitHub does not auto-link them to issues.

## Review consistency checklist

Use this checklist when reviewing a job/pipeline change:

- [ ] **Naming**: paths follow `<org>/<repo>/<branch>/<job>`; Prow files are named
  `<branch>-<jobtype>.yaml`; each job folder holds `dsl.groovy`, `Jenkinsfile` and an
  optional `pod.yaml` / `pod-<purpose>.yaml` (no job name repeated in the file name).
- [ ] **References**: `scriptPath` resolves to the pipeline file, and every
  `POD_TEMPLATE_FILE` resolves to an existing `pod*.yaml` (`pod.yaml` or `pod-<purpose>.yaml`).
- [ ] **Pod templates and image tags**: pod YAML is a valid Pod manifest, and image tags
  use the intended variant suffix (for example `-dind`, `-alpine`) rather than a
  hand-edited tag.
- [ ] **Three-layer consistency**: Prow trigger, Job DSL (`pipelineJob` name) and
  pipeline agree on the job name and branch.
- [ ] **Approvals**: CI infrastructure changes require `sig-approvers-ee`; job configs
  require the owning project SIG, per `OWNERS` and `OWNERS_ALIASES`.
- [ ] **Generated manifests**: after editing Prow or Tekton YAML, run the kustomization
  update script so generated manifests stay in sync.
- [ ] **Evidence**: the PR includes static validation results and, where behavior
  changed, staging/replay results.

## Verification commands

```bash
# Jenkins pipeline syntax/model validation
.ci/verify-jenkins-pipelines.sh

# Pod YAML structural validation
.ci/verify-k8s-pod-yaml.sh

# Regenerate generated manifests after Prow/Tekton edits
.ci/update-prow-job-kustomization.sh
.ci/update-tekton-kustomizations.sh

# Replay changed pipelines against a Jenkins instance
.ci/replay-jenkins-build.sh --auto-changed --jenkins-url https://prow.tidb.net/jenkins --verbose
```

See [CI.md](./CI.md#pre-pr-verification-for-jenkins-pipeline-changes) for full examples,
including the Prow presubmit jobs that run these checks automatically.

## Conductor track template

A minimal track for a job/pipeline change:

```markdown
# Specification: <short description>

## Goal
<What changes and why.>

## Scope
<Affected repos/branches and the three layers touched.>

## Acceptance Criteria
- <Observable result, for example a specific job passes in staging.>
```

```markdown
# Implementation Plan: <short description>

## Phase 1: Implement and verify
- [ ] Task: Update the affected layers
    - [ ] Update the Prow trigger (if needed)
    - [ ] Update the Job DSL and pipeline
    - [ ] Update the pod template (if needed)
- [ ] Task: Run verification
    - [ ] `.ci/verify-jenkins-pipelines.sh`
    - [ ] Replay/staging test and record the build URL
- [ ] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md)
```

## See Also

- [Contributing](../contributing.md) — staging-to-production mechanics
- [CI guide](./CI.md) — finding, modifying and testing pipelines
- [Testing Library Code](./testing-library-code.md) — unit tests for shared library functions
