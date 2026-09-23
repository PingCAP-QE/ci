# Jenkins Job Folder Layout

This guide explains the one-folder-per-job layout for Jenkins jobs: where the
files live, how they reference each other, and how to add or change a job. It
also points at the migration runbook for converting the remaining legacy jobs.

## Introduction

Historically a single Jenkins job was spread across two directory trees:

```
jobs/<org>/<repo>/<branch>/<job>.groovy         # Jenkins Job DSL (pipelineJob)
pipelines/<org>/<repo>/<branch>/<job>.groovy    # declarative pipeline
pipelines/<org>/<repo>/<branch>/pod-<job>.yaml  # Kubernetes pod template (optional)
```

Changing one job meant locating three files in two trees, and the DSL and
pipeline files shared the `<job>.groovy` name. The new layout puts every
artifact of a job in a single folder under `jenkins/`.

## Target Layout

```
jenkins/
└── jobs/<org>/<repo>/<branch>/<job>/
    ├── dsl.groovy     # Jenkins Job DSL (pipelineJob)
    ├── Jenkinsfile    # declarative pipeline
    └── pod.yaml       # Kubernetes pod template (optional)
```

All Jenkins job artifacts live under a single top-level `jenkins/` directory, so
the repository reads as `prow-jobs/` (triggers), `jenkins/` (Jenkins backend),
and `tekton/` (CD).

### File names

| File | Purpose | Required | Notes |
|---|---|---|---|
| `dsl.groovy` | Jenkins Job DSL (`pipelineJob`) | yes | exactly one per job folder |
| `Jenkinsfile` | declarative pipeline | yes | exactly one per job folder |
| `pod.yaml` | Kubernetes pod template | no | when the job has exactly one template |
| `pod-<purpose>.yaml` | Kubernetes pod template | no | when the job has several (`pod-build.yaml`, `pod-test.yaml`, `pod-main.yaml`, ...) |
| `aa_folder.groovy` | folder definition | no | not a job; one level above the job folders |

Rules:

- A job with a single pod template uses `pod.yaml`.
- A job with multiple templates uses `pod-<purpose>.yaml`, one per template.
- A job with no pod template omits the file entirely.
- Job name format: `[a-z][a-z0-9_]*[a-z0-9]`.

## Finding a Job

For a repository such as `pingcap/tidb` on branch `latest`, look in:

- `/prow-jobs/pingcap/tidb/` - trigger configuration
- `/jenkins/jobs/pingcap/tidb/latest/<job>/` - the job itself

Example: the `pull_unit_test` job lives at
`jenkins/jobs/pingcap/tidb/latest/pull_unit_test/` and contains `dsl.groovy`,
`Jenkinsfile`, and `pod.yaml`.

## Adding or Modifying a Job

1. Create or open the job folder
   `jenkins/jobs/<org>/<repo>/<branch>/<job>/`.
2. Put the Job DSL in `dsl.groovy`. Its `scriptPath` must point at the sibling
   `Jenkinsfile`, using a repository-root-relative path:

   ```groovy
   definition {
       cpsScm {
           lightweight(true)
           scriptPath("jenkins/jobs/pingcap/tidb/latest/pull_unit_test/Jenkinsfile")
           scm {
               git {
                   remote { url('https://github.com/PingCAP-QE/ci.git') }
                   branch('main')
               }
           }
       }
   }
   ```

3. Put the declarative pipeline in `Jenkinsfile`. When the job has a pod
   template, point the pod constant at the sibling file:

   ```groovy
   final POD_TEMPLATE_FILE = "jenkins/jobs/pingcap/tidb/latest/pull_unit_test/pod.yaml"
   // ...
   yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
   ```

4. Put the pod template in `pod.yaml` (or `pod-<purpose>.yaml` for a multi-pod
   job). Omit it when the job has no pod template.
5. Update the Prow trigger in `/prow-jobs/<org>/<repo>/` if the job itself
   changed, following [Job and Pipeline Change Governance](./job-change-governance.md).
6. Run the reference checker before opening a PR:

   ```bash
   .ci/check-jenkins-job-references.sh
   ```

### Reference rules

- All `scriptPath` and pod-template references are **repository-root-relative**;
  never use `../` relative references. The checker resolves them from the
  repository root, and Jenkins resolves `cpsScm.scriptPath` the same way.
- Both literal and templated forms are supported:

  ```groovy
  scriptPath("jenkins/jobs/${fullRepo}/${branchAlias}/${jobName}/Jenkinsfile")
  final POD_TEMPLATE_FILE = "jenkins/jobs/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
  ```

- Constant **names** (`POD_TEMPLATE_FILE`, `MAIN_POD_TEMPLATE_FILE`,
  `TEST_POD_TEMPLATE_FILE`, ...) may stay as they are; only their values change.

## The Legacy Trees

The legacy `/jobs/**` and `/pipelines/**` trees are being retired by the
one-folder-per-job migration. Do not add new jobs there. During the migration:

- The Jenkins seed job discovers jobs in **both** `jobs/**/*.groovy` and
  `jenkins/jobs/**/*.groovy`, so a partially migrated repository is always fully
  discovered.
- The migration **moves** the DSL, pipeline and pod templates into the job
  folder, so no legacy duplicate is left behind. A source still referenced by a
  not-yet-migrated job is copied instead of moved, and the last referencing job
  moves it away.
- `.ci/check-jenkins-job-references.sh` fails when a job is defined in both
  trees, so the two layouts can never both own a job.

To convert a slice of jobs, follow the
[Jenkins Job Folder Migration Runbook](./jenkins-job-folder-migration-runbook.md).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `check-jenkins-job-references.sh` reports a dangling `scriptPath` | The `Jenkinsfile` path in `dsl.groovy` does not exist | Fix the path or add the missing `Jenkinsfile` |
| The checker reports a dangling pod reference | `POD_TEMPLATE_FILE` points at a missing file | Fix the constant or add the pod template |
| The checker reports a job defined in both layouts | The legacy DSL was not moved | Re-run `scripts/migrate-jenkins-jobs.sh --apply` |
| The checker reports a job folder without a `Jenkinsfile` | Incomplete migration | Restore or add the `Jenkinsfile` |
| Orphaned artifact warnings | A pipeline/pod file no job references | Expected for pre-existing orphans until `--cleanup` removes the retired tree; use `--strict` in CI after cleanup |

## See Also

- [Design: Co-locate Jenkins Job Artifacts in One Folder per Job](../designs/jenkins-job-folder-layout.md)
- [Jenkins Job Folder Migration Runbook](./jenkins-job-folder-migration-runbook.md)
- [Job and Pipeline Change Governance](./job-change-governance.md)
- [CI Guide](./CI.md)
