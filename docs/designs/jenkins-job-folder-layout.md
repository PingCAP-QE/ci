# Design: Co-locate Jenkins Job Artifacts in One Folder per Job

- **Status:** Proposed
- **Track:** `jenkins_job_layout_20260916`
- **Scope:** design + tooling only. The full migration of all jobs is a
  follow-up track (see [Rollout](#12-rollout-plan)).

## 1. Context

A single Jenkins job is currently spread across two directory trees:

```
jobs/<org>/<repo>/<branch>/<job>.groovy              # Jenkins Job DSL (pipelineJob)
pipelines/<org>/<repo>/<branch>/<job>.groovy         # declarative pipeline
pipelines/<org>/<repo>/<branch>/pod-<job>.yaml       # Kubernetes pod template (optional)
```

The DSL file references the pipeline with `scriptPath`, and the pipeline
references its pod template (usually via `POD_TEMPLATE_FILE`). Changing one job
therefore means locating three files in two trees, and the DSL and pipeline
files share the same `<job>.groovy` name, so they cannot simply be dropped into
one folder without renaming.

Current scale (see the track inventory note for the full breakdown):

- ~385 Job DSL files, ~374 pipeline scripts, ~408 pod templates.
- 369 job folders: 301 flat + 68 nested.
- 15 nested jobs carry **more than one** pod template (`pod-build.yaml` +
  `pod-test.yaml`, or `main-pod.yaml` + `test-pod.yaml`).
- The reference graph was not clean at the start: the reference checker found 11
  dangling `scriptPath` targets and 15 orphaned artifacts, plus pod references
  that used runtime variables. The 11 dangling DSLs were retired as dead jobs
  (their pipelines never existed, or were removed when the jobs moved to Prow),
  and the resolver now maps `${REFS.org}`/`${REFS.repo}` to the job's
  `<org>/<repo>`. 11 pre-existing orphaned pipeline/pod files remain; they are
  removed with the `pipelines/` tree at cleanup.

## 2. Goals

1. One folder per job containing every artifact needed to understand and change
   that job.
2. Predictable, collision-free file names inside the folder.
3. Machine-checkable references: no dangling `scriptPath` / pod-template paths.
4. A safe, reversible migration path with shared-source handling and a cleanup
   gate.

## 3. Non-Goals

- Changing Jenkins job names, Prow triggers, pipeline logic, or runtime behavior.
- Restructuring Tekton resources or Prow job YAML.
- Moving `libraries/` (Jenkins shared library). It stays at the repository root;
  relocating it is a separate follow-up track (see section 4).
- Executing the full migration in this track.

## 4. Target layout

All Jenkins job artifacts move under a single top-level `jenkins/` directory, so
the repository reads as `prow-jobs/` (triggers), `jenkins/` (Jenkins backend),
`tekton/` (CD):

```
jenkins/
└── jobs/<org>/<repo>/<branch>/<job>/
    ├── dsl.groovy     # Jenkins Job DSL (pipelineJob); was jobs/.../<job>.groovy
    ├── Jenkinsfile    # declarative pipeline; was pipelines/.../<job>.groovy
    └── pod.yaml       # Kubernetes pod template (OPTIONAL); was pipelines/.../pod-<job>.yaml
```

`aa_folder.groovy` files are **not** jobs and stay one level above the job
folders: `jenkins/jobs/<org>/<repo>/<branch>/aa_folder.groovy`.

`libraries/` is **out of scope** and stays at the repository root. Moving it
would couple this change to the Jenkins controller's global library
configuration (`libraryPath('libraries/tipipeline')` in `aa_folder.groovy`),
every `@Library('tipipeline')` annotation, and the library tests. It is a
candidate for a dedicated follow-up track once `jenkins/jobs/` has landed.

### 4.1 Naming rules

| Artifact | Name | Rules |
|---|---|---|
| Job DSL | `dsl.groovy` | exactly one per job folder |
| Pipeline | `Jenkinsfile` | exactly one per job folder |
| Pod template | `pod.yaml` | when the job has exactly one template |
| Pod template | `pod-<purpose>.yaml` | when the job has more than one (`pod-build.yaml`, `pod-test.yaml`, `pod-main.yaml`, ...) |

**Multi-pod decision.** The specification assumed a single optional `pod.yaml`.
The inventory shows multi-pod jobs are common (ticdc/tiflow/tiflash build+test
pods; tikv/pd main+test pods). To keep names predictable *and* collision-free:

- A job with a single pod template uses `pod.yaml`.
- A job with multiple templates uses `pod-<purpose>.yaml`, where `<purpose>` is
  a short slug (`build`, `test`, `main`, ...).
- Existing non-conforming names (`main-pod.yaml`, `test-pod.yaml`,
  `pod-*.yaml` from flat jobs) are normalized during migration; the referencing
  constants (`MAIN_POD_TEMPLATE_FILE`, `POD_TEMPLATE_FILE_BUILD`, ...) are
  rewritten to the new paths. Constant **names** are not required to change.

## 5. Reference model

### 5.1 `scriptPath`

`cpsScm.scriptPath` is resolved relative to the repository root, so it can point
into `jenkins/jobs/`. Every migrated DSL keeps a single named reference,
`ciGroovyPath`, and calls `scriptPath(ciGroovyPath)`:

```groovy
final fullRepo = 'pingcap/tidb'
final branchAlias = 'latest'
final jobName = 'pull_unit_test'
final ciGroovyPath = "jenkins/jobs/${fullRepo}/${branchAlias}/${jobName}/Jenkinsfile"
// ...
scriptPath(ciGroovyPath)
```

The migration templates `ciGroovyPath` from the DSL's own `final` variables when
they reproduce the target path, and falls back to the literal path when they do
not (non-standard job names, missing variables). This keeps a single
maintainable reference instead of a hardcoded string, and removes the stale
`ciGroovyPath` values that pointed at the old `pipelines/` tree.

### 5.2 Pod templates

`pod_label.withCiLabels(<path>, REFS)` resolves `<path>` with `readTrusted`,
i.e. relative to the workspace root. Pod paths therefore also become
`jenkins/jobs/<org>/<repo>/<branch>/<job>/pod.yaml` (or `pod-<purpose>.yaml`).

```groovy
final POD_TEMPLATE_FILE = "jenkins/jobs/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
// multi-pod:
final MAIN_POD_TEMPLATE_FILE = "jenkins/jobs/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod-main.yaml"
final TEST_POD_TEMPLATE_FILE = "jenkins/jobs/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod-test.yaml"
```

Both reference types remain simple string constants, so both literal and
templated forms are mechanically rewritable.

For static checking, pod paths that reference runtime PR variables
(`${REFS.org}` / `${REFS.repo}`) are resolved to the job's `<org>/<repo>` path
components, which is what those variables evaluate to for the owning job.

## 6. Edge cases

| Case | Handling |
|---|---|
| Job without a pod template (14 flat + 2 nested) | No `pod.yaml`; remove the `POD_TEMPLATE_FILE` constant when present, otherwise leave the pipeline untouched. |
| Job with multiple pod templates (15 nested) | `pod-<purpose>.yaml` names; rewrite each constant. |
| Existing nested job dirs (68) | Already folder-shaped; rename `pipeline.groovy` -> `Jenkinsfile`, co-locate `dsl.groovy`, normalize pod names. |
| Flat job with a `.groovy` pipeline | rename `<job>.groovy` -> `Jenkinsfile`. |
| Shared/non-job dir (`pipelines/pingcap-inc/tiflash-scripts/latest/common/`) | Report, do not migrate. |
| Dangling `scriptPath` (16) | Report as pre-existing; never auto-delete. Migration emits a per-job warning and leaves it for manual handling. |
| Pipeline without DSL (5) | Report as orphaned artifact; do not delete until the cleanup gate. |
| Hyphenated job name (`pr_verify` / `pr-verify`) | Match by DSL filename; report the name mismatch. |
| Non-`<org>/<repo>/<branch>` paths (`jobs/qa/...`) | Report; migrate only after manual confirmation. |
| `aa_folder.groovy` | Preserve in place; never treat as a job. |

## 7. Migration phases

1. **Dry-run (default):** classify every DSL/pipeline/pod, print planned
   moves/renames and every reference rewrite, and list unrecognized files. No
   filesystem changes.
2. **Apply:** create job folders, move each job's DSL, pipeline and pod templates
   into its folder, and rewrite references. No back-compat symlinks are created
   and no legacy duplicate is left behind; a source still referenced by a
   not-yet-migrated job is copied instead of moved. Idempotent; re-running is a
   no-op.
3. **Verify:** run the reference-integrity checker, pipeline syntax validation,
   pod-manifest validation, and a staging replay on the migrated slice.
4. **Cleanup (`--cleanup`):** remove any remaining legacy `pipelines/` tree
   (unmigrated files and orphans) and prune the now-empty legacy `jobs/` tree,
   only after the checker is clean and the replay passed.

### 7.1 No back-compat symlinks; dual-tree discovery

An earlier revision kept back-compat symlinks at the legacy paths. That was
dropped for two reasons:

- The Jenkins seed job runs with `removedJobAction('DELETE')`, so any job the
  seed fails to discover is **deleted**. A symlink-based back-compat layer turns
  a single missing symlink into a deleted Jenkins job — too sharp an edge for a
  bulk migration.
- Symlink behavior differs across tools (git checkout, glob, Jenkins Job DSL
  target resolution) and is hard to validate exhaustively.

Instead, discovery is made layout-independent:

- The seed job scans **both** trees:
  `targets('jobs/**/*.groovy\njenkins/jobs/**/*.groovy')`, configured in
  `PingCAP-QE/ee-ops` (JCasC). A partially migrated repository is therefore
  always fully discovered, and the legacy pattern degrades to a no-op once the
  migration finishes.
- The migration **moves** the job DSL, pipeline and pod templates into the job
  folder, so a job is never defined twice and no legacy duplicate is left
  behind. A source that a not-yet-migrated job still references (a genuinely
  shared pipeline/pod) is copied instead of moved, and the last job to reference
  it moves it away.
- The checker fails when a job is defined in both trees, so the two layouts can
  never both own a job.

**Accepted trade-off.** Moving the pipeline removes the window in which a build
that starts before the seed re-indexes still resolves its old `scriptPath`. An
earlier revision copied the pipeline and kept the legacy copy until `--cleanup`;
that left the entire legacy tree duplicated (797 files on the fully migrated
tree), which is not worth the short window. The seed re-indexes on merge, so the
window is small, and no back-compat copy is kept.

### 7.2 Cleanup gate

`--cleanup` refuses to run unless the following hold:

1. `.ci/check-jenkins-job-references.sh` exits 0 (always enforced).
2. `.ci/verify-jenkins-pipelines.sh` passes on the new layout (enforced when
   `JENKINS_URL` is set; otherwise reported as a manual precondition).
3. `.ci/verify-k8s-pod-yaml.sh` passes on the new layout (enforced when `yq` is
   available; otherwise reported as a manual precondition).
4. A staging replay of the migrated jobs succeeded and is recorded (manual).

## 8. Tooling

### 8.1 Reference-integrity checker: `.ci/check-jenkins-job-references.sh`

- Scans every `jenkins/jobs/**` job folder.
- Resolves each `scriptPath` (literal and templated) to a real `Jenkinsfile`.
- Resolves each `POD_TEMPLATE_FILE*` (literal and templated) to a real pod file.
- Fails non-zero with actionable output when a target is missing.
- Flags orphaned artifacts (a pipeline/pod with no referencing job) as warnings,
  with a `--strict` mode that turns them into failures for CI.
- Fails when a job folder is incomplete (`dsl.groovy` without `Jenkinsfile`).
- Fails when the same job is defined in both layouts (`jobs/` and
  `jenkins/jobs/`), which is what the dual-tree seed discovery relies on.
- Ships with a fixture-based test: `.ci/test-check-jenkins-job-references.sh`
  covering valid refs, dangling `scriptPath`, dangling pod ref, orphaned
  artifact, a job without a pod template, a job defined in both layouts, and a
  job folder without a `Jenkinsfile`.

### 8.2 Migration tool: `scripts/migrate-jenkins-jobs.sh`

- `--dry-run` (default) / `--apply` / `--cleanup`.
- Per job: create the job folder, move the DSL to `dsl.groovy`, move the pipeline
  to `Jenkinsfile` and the pod templates next to it, rewrite `scriptPath` and the
  pod constants, and emit a summary. No symlinks, no residual copy. A pipeline or
  pod still referenced by an unmigrated job is copied instead of moved.
- Idempotent and re-run safe: the legacy DSL is moved away, so a second `--apply`
  finds nothing to migrate.
- Ships with a sandbox fixture repo under `tests/fixtures/` and a test that
  asserts planned moves/renames, reference rewrites, absence of symlinks, the
  no-pod job case, shared-source handling (first job copies, last job moves),
  idempotency, and cleanup guarding.

### 8.3 Verification script update: `.ci/verify-jenkins-pipelines.sh`

Repoint discovery from `find pipelines -name "*.groovy"` to
`find jenkins/jobs -name "Jenkinsfile"` (excluding `aa_folder.groovy`, which is
not validated as a pipeline anyway). `.ci/verify-k8s-pod-yaml.sh` is repointed
from `find pipelines -type f -name '*.yaml'` to
`find jenkins/jobs -type f -name 'pod*.yaml'`.

### 8.4 Other consumers

`.ci/replay-jenkins-build.sh`, `.ci/verify-jenkins-credential-policy.sh` and its
test, the `.agents` replay skill, and `.github/renovate.json` all encode
`pipelines/*` path assumptions. They are repointed in the hardening change that
lands before the migration batches, together with the Prow presubmit
`run_if_changed` filters and the seed postsubmit trigger. The external seed job
(`PingCAP-QE/ee-ops`) is updated in lockstep; it lives outside this repository.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Seed job misses a migrated job and deletes it (`removedJobAction('DELETE')`) | Seed scans both `jobs/**` and `jenkins/jobs/**`, so discovery is independent of migration progress. |
| Stale `scriptPath` before the seed re-indexes | Accepted: the seed re-indexes on merge. Pipelines are moved (not copied), so old paths stop resolving once the migration merges; no legacy tree is retained to duplicate the repository. |
| A job defined in both layouts generates two different configs | The migration moves the DSL, and the checker fails on a job defined in both trees. |
| Merge conflicts with in-flight PRs touching moved files | Migration runs in small per-repo batches. |
| Multi-pod jobs mis-mapped to a single `pod.yaml` | Explicit `pod-<purpose>.yaml` rule + checker fails on duplicate/missing targets. |
| Pre-existing dangling references confuse the checker | Baseline is recorded; the checker distinguishes pre-existing from introduced orphans and never auto-deletes. |
| Jenkins controller library config if `libraries/` moved | Out of scope: `libraries/` stays at the repository root. |

## 10. Rollback

1. Do not run `--cleanup` until verification passes; before cleanup, `git`
   history is the primary rollback (the DSL/pipeline/pod moves are plain file
   changes).
2. Revert the migration commit(s) and the reference rewrites together.
3. Re-run `.ci/check-jenkins-job-references.sh` to confirm the revert restored a
   consistent state.

## 11. Proposed convention updates (draft, content only)

These are drafts. `AGENTS.md` and `conductor/code_styleguides/` are updated in
the rollout phase, once the migration tooling exists.

### 11.1 `AGENTS.md`

- Repository structure: replace the separate `jobs/` + `pipelines/` entries with
  a `jenkins/` entry containing the one-folder-per-job layout.
- File naming:
  `jenkins/jobs/<org>/<repo>/<branch>/<job>/{dsl.groovy,Jenkinsfile,pod.yaml|pod-<purpose>.yaml}`.
- Common task "Adding/Modifying CI Jobs": edit the single job folder.

### 11.2 `conductor/code_styleguides/`

Add a Jenkins job-layout section stating the file names, the single/multi pod
rule, and that all `scriptPath` / pod references are repo-root-relative.

## 12. Rollout plan

1. Hardening (this change): migration tool + checker + no-symlink redesign,
   consumer repointing (replay, credential policy, renovate, `.agents` skill),
   Prow `run_if_changed` filters and the seed postsubmit trigger, design doc.
2. Seed: `PingCAP-QE/ee-ops` scans both trees (JCasC `targets`). Must be deployed
   before the legacy `jobs/` tree is retired.
3. Migration batches (this track's stacked PRs, rebased on the hardening change):
   `--apply` one `<org>/<repo>` slice at a time, checking the seed output after
   each merge.
4. Cleanup (`--cleanup`): after every job is migrated, the checker is clean, the
   syntax/pod validation and a staging replay have passed.
5. Follow-ups: move `libraries/` under `jenkins/`; retire the now-unused legacy
   `jobs/**` pattern from the seed `targets`.
