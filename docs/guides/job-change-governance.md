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
2. **Plan** — write the plan down before touching files: a short plan in the PR
   description, or a design doc under `docs/designs/` when the change alters the file
   layout, a shared library or a rollout/serving path. The plan records what changes and
   which layers are touched, and it is the source of truth for the work. Planning notes
   stay with the change; keep private or tool-specific scratch files out of the
   repository.
3. **Implement** — work through the plan task by task, in reviewable commits that leave
   each phase verifiable on its own.
4. **Verify** — run the static validators and, for pipeline behavior, a staging/replay
   test. Keep the commands and results.
5. **Promote** — follow the staging-to-production flow in
   [Contributing](../contributing.md) and attach test evidence (build links, command
   output) to the promotion PR.

This lifecycle maps onto the pipeline workflow in [CI](./CI.md):

| Governance step | [CI guide](./CI.md) / [Contributing](../contributing.md) step |
|---|---|
| Propose + Plan | Identify the pipeline, describe the change, write the plan (PR description or design doc) |
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

## Planning notes and evidence

- Keep the plan in the PR description (or the design doc) as a task list: mark a task
  `[~]` when starting and `[x]` when done, so review progress is visible without
  reading Git history.
- Record verification evidence in the PR itself — the exact validation commands and
  their results, plus any staging/replay build URLs — so it survives after the branch
  is deleted.
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

# Pod resource policy: memory requests == limits (Guaranteed QoS) for changed
# Pod templates; exceptions live in .ci/pod-resource-policy-allowlist.txt
.ci/verify-pod-resource-policy.sh

# Regenerate generated manifests after Prow/Tekton edits
.ci/update-prow-job-kustomization.sh
.ci/update-tekton-kustomizations.sh

# Replay changed pipelines against a Jenkins instance
.ci/replay-jenkins-build.sh --auto-changed --route-by-prow-master --verbose
```

See [CI.md](./CI.md#pre-pr-verification-for-jenkins-pipeline-changes) for full examples,
including the Prow presubmit jobs that run these checks automatically.

## Change plan template

A minimal plan for a job/pipeline change, written into the PR description (or the top of
a design doc):

```markdown
## What and why
<What changes, and the problem it solves.>

## Scope
<Affected repos/branches and the three layers touched.>

## Acceptance criteria
- <Observable result, for example a specific job passes in staging.>

## Phases
- [ ] Update the affected layers
    - [ ] Update the Prow trigger (if needed)
    - [ ] Update the Job DSL and pipeline
    - [ ] Update the pod template (if needed)
- [ ] Run verification
    - [ ] `.ci/verify-jenkins-pipelines.sh`
    - [ ] Replay/staging test, with the build URL recorded
```

## See Also

- [Contributing](../contributing.md) — staging-to-production mechanics
- [CI guide](./CI.md) — finding, modifying and testing pipelines
- [Testing Library Code](./testing-library-code.md) — unit tests for shared library functions
