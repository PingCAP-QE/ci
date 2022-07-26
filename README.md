# PingCap CI/CD public configurations

## New top level directories

- `/jobs` store Jenkins CI job DSL files.
- `/pipelines` store Jenkins CI pipeline scripts.
- `/libraries` store Jenkins CI shared libraries.

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