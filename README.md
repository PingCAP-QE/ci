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
- https://cd.pingcap.net

## New top level directories

- `/docs` documents about CI/CD jobs, tools, etc.
- `/prow-jobs` store the top level prow jobs.
- `/jobs` store Jenkins CI job DSL files. some jobs will be called by prow jobs defined in `/prow-jobs`.
- `/pipelines` store Jenkins CI pipeline scripts.
- `/libraries` store Jenkins CI shared libraries.
- `/staging` store staging jobs and pipelines before deploying to production.
  - `/staging/jobs` like `/jobs` but only deployed to staging env.
  - ......

## File structure for jobs and pipelines

- using `<org>/<repo>/<branch-special>/<pipeline-name>.groovy` structure.
  - the `lastet` store CI jobs and scripts for trunk branch and feature branches.
  - the `release-x.y` store CI jobs and scripts for the special release branch.

## Job DSL important usage

- Do not use light checkout: avoid soft link in git.

## Old directories(migrating)

- `/jenkins/jobs` store migrated or migrating Jenkins CI job DSL files for server ci.pingcap.net, ci2.pingcap.net and cd.pingcap.net.
- `/jenkins/pipelines` store Jenkins CI pipeline scripts for server ci.pingcap.net, ci2.pingcap.net and cd.pingcap.net.
