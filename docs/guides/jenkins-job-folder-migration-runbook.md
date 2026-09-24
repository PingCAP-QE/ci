# Jenkins Job Folder Migration Runbook

This runbook converts the remaining legacy Jenkins jobs to the
one-folder-per-job layout, verifies the result, and retires the legacy trees.
It is the operational companion to
[Design: Co-locate Jenkins Job Artifacts in One Folder per Job](../designs/jenkins-job-folder-layout.md)
and the [Jenkins Job Folder Layout](./jenkins-job-folder-layout.md) guide.

## Introduction

The migration tool `scripts/migrate-jenkins-jobs.sh` **moves** each job's DSL,
pipeline and pod templates into
`jenkins/jobs/<org>/<repo>/<branch>/<job>/` (`dsl.groovy`, `Jenkinsfile`,
`pod.yaml`/`pod-<purpose>.yaml`) and rewrites the `scriptPath` and pod-template
references. A source still referenced by a not-yet-migrated job is copied
instead of moved, and the last referencing job moves it away. It runs one slice
at a time so each batch is small enough to review.

## Prerequisites

- The tooling is on `main`:
  - `scripts/migrate-jenkins-jobs.sh`
  - `.ci/check-jenkins-job-references.sh`
- **The Jenkins seed job scans both trees.** The seed job in
  `PingCAP-QE/ee-ops` (JCasC) must discover `jobs/**/*.groovy` **and**
  `jenkins/jobs/**/*.groovy`. This must be deployed before any migration batch
  is merged; it is what makes a partially migrated repository fully discovered.
  The seed runs with `removedJobAction('DELETE')`, so a job it cannot discover
  is deleted.
- A clean working tree, and the standard Jenkins credentials for the
  verification steps below (`JENKINS_URL`, `JENKINS_USER`, `JENKINS_TOKEN`).

## Safety Model

- **No back-compat symlinks or copies.** The DSL, pipeline and pod templates are
  *moved* (so a job is never defined twice and no legacy duplicate is left
  behind). A source shared by a not-yet-migrated job is copied for the earlier
  job and moved by the last one.
- **Accepted trade-off:** moving removes the window in which a build that starts
  before the seed re-indexes still resolves its old `scriptPath`. The seed
  re-indexes on merge, so the window is small.
- **Idempotent.** Re-running `--apply` on a migrated slice is a no-op; it never
  overwrites an existing job folder.
- **Cleanup is gated** on a clean reference check.

## Step 1: Dry-run a Slice

Run the tool in dry-run mode (the default) for the slice you intend to migrate.
Use the narrowest `--only` scope that makes sense, usually one repository:

```bash
scripts/migrate-jenkins-jobs.sh --dry-run --only <org>/<repo>
```

`--only` accepts `<org>/<repo>[/<branch>[/<job>]]`. Review the report:

- Every planned move and rename.
- Every rewritten `scriptPath` and pod-template reference.
- Any `WARN` lines for non-standard jobs, name mismatches, or skipped files.

Resolve or explicitly accept every warning before applying. A non-standard job
should be migrated manually or retired, not forced through the tool.

## Step 2: Apply the Slice

```bash
scripts/migrate-jenkins-jobs.sh --apply --only <org>/<repo>
```

This creates the job folders, moves each DSL to `dsl.groovy`, the pipeline to
`Jenkinsfile` and the pod templates next to it, and rewrites the references.
Open a PR for the slice and merge it bottom-up in the stack before moving to the
next slice.

## Step 3: Verify the Slice

Run the static reference check first:

```bash
.ci/check-jenkins-job-references.sh
```

It must exit 0 with no dangling `scriptPath` or pod references. (Orphaned
artifact warnings are expected until `--cleanup`.)

Then run the Jenkins-backed checks:

```bash
# Pipeline syntax/model validation for the new and legacy layouts
JENKINS_URL=https://prow.tidb.net/jenkins .ci/verify-jenkins-pipelines.sh

# Pod manifest validation for the changed pod templates
.ci/verify-k8s-pod-yaml.sh jenkins/jobs/<org>/<repo>/<branch>/<job>/pod.yaml

# Replay one migrated job against a historical build
JENKINS_USER="<jenkins-user>" \
JENKINS_TOKEN="<jenkins-token>" \
.ci/replay-jenkins-build.sh \
  --script-file jenkins/jobs/<org>/<repo>/<branch>/<job>/Jenkinsfile \
  --jenkins-url https://prow.tidb.net/jenkins \
  --selector lastSuccessfulBuild \
  --wait \
  --verbose
```

Alternatively, replay every changed pipeline in the working tree:

```bash
JENKINS_USER="<jenkins-user>" \
JENKINS_TOKEN="<jenkins-token>" \
.ci/replay-jenkins-build.sh \
  --auto-changed \
  --jenkins-url https://prow.tidb.net/jenkins \
  --selector lastSuccessfulBuild \
  --wait \
  --verbose
```

Record the pipeline-validation and replay results in the PR. These steps require
network access to the Jenkins controller and are the blocking precondition for
cleanup.

### Confirm the migrated layout

The migration moves the artifacts, so after `--apply` the new job folder holds
`dsl.groovy`, `Jenkinsfile` and any pod templates, and the legacy path is gone:

```bash
test -f jenkins/jobs/<org>/<repo>/<branch>/<job>/Jenkinsfile && echo "new layout present"
test ! -e pipelines/<org>/<repo>/<branch>/<job>.groovy && echo "no legacy duplicate"
```

For a source shared by several jobs (for example a `latest` pipeline also
referenced from a `dedicated` job), the earlier job copies it and the last job
moves it, so the legacy path is gone once every referencing job has migrated.

## Step 4: Cleanup (after every job is migrated)

Cleanup removes the legacy `pipelines/` tree and prunes the emptied `jobs/`
tree. It refuses to run unless `.ci/check-jenkins-job-references.sh` is clean.

```bash
# Checker must be clean first
.ci/check-jenkins-job-references.sh

# Remove the legacy trees
scripts/migrate-jenkins-jobs.sh --cleanup
```

Cleanup is a documented, separately reviewed change. Before it is merged, the
following must all hold:

1. `.ci/check-jenkins-job-references.sh` exits 0.
2. `.ci/verify-jenkins-pipelines.sh` passes on the new layout (when
   `JENKINS_URL` is set).
3. `.ci/verify-k8s-pod-yaml.sh` passes on the new layout (when `yq` is
   available).
4. A staging replay of the migrated jobs succeeded and is recorded in the PR.

After cleanup, the orphaned-artifact warnings disappear and the checker can be
run with `--strict` in CI.

## Rollback

Rollback is plain Git history until `--cleanup` runs:

1. **Before cleanup:** revert the migration commit(s) and the reference rewrites
   together, e.g.

   ```bash
   git revert --no-commit <migration-commit>
   git revert --no-commit <reference-rewrite-commit>
   git commit -m "revert: restore the legacy Jenkins layout for <org>/<repo>"
   ```

   The DSL/pipeline/pod moves are ordinary file changes, so a revert restores
   the legacy tree exactly.

2. **After cleanup:** restore the deleted `pipelines/` and `jobs/` trees from
   Git history:

   ```bash
   git checkout <pre-cleanup-sha> -- pipelines jobs
   ```

   The seed job scans both trees, so restoring the legacy tree does not hide the
   migrated jobs; remove the corresponding `jenkins/jobs/...` folders in the same
   change to avoid a job being defined twice.

3. **Always re-check after a rollback:**

   ```bash
   .ci/check-jenkins-job-references.sh
   ```

   It must exit 0 before the rollback is merged.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Dry-run reports `WARN` for a job | Non-standard path, name mismatch, or shared directory | Migrate or retire it manually; do not force |
| `--apply` reports `UP-TO-DATE` | The job folder already exists | Expected on re-run; verify with the checker |
| Cleanup refuses to run | The checker found dangling references | Fix the references, then re-run cleanup |
| A migrated job disappears from Jenkins | The seed job did not discover it | Confirm the seed scans `jenkins/jobs/**`; check the seed output |
| A build that started before the seed re-indexed fails on its old `scriptPath` | The pipeline was moved and no legacy copy is kept | Accepted trade-off; the seed re-indexes on merge, so re-run the build |

## See Also

- [Design: Co-locate Jenkins Job Artifacts in One Folder per Job](../designs/jenkins-job-folder-layout.md)
- [Jenkins Job Folder Layout](./jenkins-job-folder-layout.md)
- [CI Guide](./CI.md)
- [Job and Pipeline Change Governance](./job-change-governance.md)
