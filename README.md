# PingCAP CI/CD public configurations

## Servers

### New

> Only one site in our roadmap.

- `do.pingcap.net`
  - https://do.pingcap.net/jenkins is the backend CI worker(Jenkins).

> Notices: when you develop new pipeplines or jobs, 
> you should put them in `/staging` folder and create a PR. When the PR merged, seed job will deploy it in staging CI server.
> When you tested with successful results, please create a PR with contents moved in top level folders and comment the PR with
> your tested job links in staging CI server.

### Old ones

- https://ci.pingcap.net
- https://ci2.pingcap.net
- https://cd.pingcap.net (internal access only)

## Directory structure

- `/docs` documents about CI/CD jobs, tools, etc.
- `/prow-jobs` store the top level prow jobs.
- `/jobs` store Jenkins CI job DSL files. some jobs will be called by prow jobs defined in `/prow-jobs`.
- `/pipelines` store Jenkins CI pipeline scripts.
- `/libraries` store Jenkins CI shared libraries.
- `/staging` store staging jobs and pipelines before deploying to production.
  - `/staging/jobs` like `/jobs` but only deployed to staging env.
  - ......
- [Deprecated] `/jenkins/jobs` store migrated or migrating Jenkins CI job DSL files for server ci.pingcap.net, ci2.pingcap.net and cd.pingcap.net.
- [Deprecated] `/jenkins/pipelines` store Jenkins CI pipeline scripts for server ci.pingcap.net, ci2.pingcap.net and cd.pingcap.net.


### File structure for jobs and pipelines

#### For prow jobs:
using `/prow-jobs/<org>/<repo>/<branch-special>-<job-type>.yaml` path to store prow jobs.

- the `<branch-special>` part is used to distinguish different branches.
  - the `latest` stores prow jobs for trunk branch and feature branches.
  - the `release-x.y`  stores prow jobs for specific release branches.
  - if all the branches have the same prow jobs, you can ignore the  `<branch-special>` part.
- the `<job-type>` part is used to distinguish different types of jobs, there are some common types:
  - `presubmits` for jobs running on pull requests.
  - `postsubmits` for jobs running on pull request merges.
  - `periodics` for jobs running on a schedule.

Also you should run the command to update the `/prow-jobs/kustomization.yaml` file for CI GitOps deployment:
```bash
# run in top folder of the repo:
.ci/update-prow-job-kustomization.sh
```

#### For Jenkins jobs:

We use folder  structure to organize Jenkins jobs.

using `/jobs/<org>/<repo>/<branch-special>/<job-type>_<job-name>.groovy` structure. Examples: 
- `/jobs/pingcap/tiflash/latest/merged_build.groovy`
- `/jobs/pingcap/tiflash/release-8.5/pull_unit_test.groovy`

- the `<branch-special>` part is used to distinguish different branches.
  - the `latest` stores Jenkins jobs for trunk branch and feature branches.
  - the `release-x.y` stores Jenkins jobs for specific release branches (like release-8.5, release-8.4, etc)
  - if all the branches have the same Jenkins jobs, you can ignore the  `<branch-special>` layer.
  - maybe you will can define some special branches like `release-x.y.z` for released path version branches to support customized jobs for hotfix branches.
- the `<job-type>` part is used to distinguish different types of jobs, there are some common types:
  - `pull` for jobs running on pull requests, it will work with the `presubmits` type prow jobs.
  - `merged` for jobs running on pull request merges, it will work with the `postsubmits` type prow jobs.
  - `periodics` for jobs running on a schedule, it will work with the `periodics` type prow jobs.
- the `<job-name>` part is used to define the job name, the naming format is `[a-z][a-z0-9_]*[a-z0-9]`.
- the `aa_folder.groovy` file will be used to define the folder name in every folder recursively, do not change the name of the file.

#### For Jenkins pipelines:

- using `/pipelines/<org>/<repo>/<branch-special>/*.groovy` file to store the Jenkins pipeline script which will be used by the Jenkins job.
  - `pipelines/pingcap/tiflash/latest/merged_build.groovy`
  - `pipelines/pingcap/tiflash/release-8.5/pull_unit_test.groovy`
- using `/pipelines/<org>/<repo>/<branch-special>/pod-*.yaml` file to store the pod template definition which will be used by the Jenkins pipeline. Examples:
  - `pipelines/pingcap/tiflash/latest/pod-pull_build.yaml`
  - `pipelines/pingcap/tiflash/release-8.5/pod-merged_build.yaml`
- the `<branch-special>` part is used to distinguish different branches, we keep it the same as the Jenkins job definition above.
- the `pod-*.yaml` part is used to distinguish different pod templates.


## More

Please refer to [docs](./docs) for details.