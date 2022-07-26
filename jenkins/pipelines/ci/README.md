# CI pipeline files

**Current only change files in `<org>/<repo>/*`!!!**
## New dir structure

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

