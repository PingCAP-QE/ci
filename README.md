# PingCap CI/CD public configurations


## Serversxxx

### New

> Only one site in our roadmap.

- https://do.pingcap.net (in development)

> Notices: when you develop new pipeplines or jobs, 
> you should put them in `/staging` folder and create a PR. When the PR merged, seed job will deploy it in staging CI server.
> When you tested with successful results, please create a PR with contents moved in top level folders and comment the PR with
> your tested job links in staging CI server.

### Old ones

- https://ci.pingcap.net
- https://ci2.pingcap.net
- https://cd.pingcap.net

## New top level directories

- `/jobs` store Jenkins CI job DSL files.
- `/pipelines` store Jenkins CI pipeline scripts.
- `/libraries` store Jenkins CI shared libraries.
- `/staging` store staging jobs and pipelines before deploying to production.
  - `/staging/jobs` like `/jobs` but only deployed to staging env.
  - ......

## File structure for jobs and pipelines

- using `<org>/<repo>/<branch-special>/<pipeline-name>.groovy` structure.
- using dir soft link to reduce duplicate pipeline files for branch special, for an example: 
  - soft link `release-6/` to `trunk/`
  - soft link `release-6.2/` to `release-6/`
  - create dir `release-6.1/` because special steps.
  - when step in release 7.x, soft link `release-7/` to `trunk/`
  - when new step should added in release 7.x
    1. detach the soft link `release-6/` to `trunk`
    2. create copy `trunk/` to `release-6/`
    3. update files in `release-7/` or `trunk`(real path).

## Job DSL important usage

- Do not use light checkout: avoid soft link in git.

## Old directories(migrating)

- `/jenkins/jobs` store migrated or migrating Jenkins CI job DSL files for server ci.pingcap.net, ci2.pingcap.net and cd.pingcap.net.
- `/jenkins/pipelines` store Jenkins CI pipeline scripts for server ci.pingcap.net, ci2.pingcap.net and cd.pingcap.net.
