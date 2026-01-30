
# CI Jobs Documentation

This directory contains documentation for CI jobs and workflows used in the project.

## Overview

The CI jobs handle automated testing, building, and deployment tasks in our continuous integration pipeline.

## Job Categories

- Build jobs
- Test jobs
- Deployment jobs `not involved yet`
- Maintenance jobs `not involved yet`

## Common Job Configurations

Jobs typically include:

- Resource requirements
- Environment dependencies `we encourage the provision of facilities via containerization`
- Triggers
- Artifacts

## Job Definitions

We use folder struct to store CI jobs for repos: `<ORG>/<REPO>`

<!-- START TOC -->
- [`pingcap/tidb`](https://github.com/pingcap/tidb) repo
  - [`master` branch](./pingccap/tidb/ci-jobs-latest.md)
  - [`release-7.1` branch](./pingccap/tidb/ci-jobs-v7.1.md)
  - [`release-6.5` branch](./pingccap/tidb/ci-jobs-v6.5.md)
- [`pingcap/tiflash`](https://github.com/pingcap/tiflow) repo. 🚧
- [`pingcap/tiflow`](https://github.com/pingcap/tiflow) repo. 🚧
- [`pingcap/ticdc`](https://github.com/pingcap/ticdc) repo. 🚧
- [`tikv/pd`](https://github.com/tikv/pd) repo
  - [`master` branch](./tikv/pd/ci-jobs-latest.md)
  - [`release-6.5` branch](./tikv/pd/ci-jbos-v6.5.md)
- [`tikv/tikv`](https://github.com/tikv/tikv) repo
  - [`master` branch] 🚧
  - [`release-6.5` branch](./tikv/tikv/ci-jobs-v6.5.md)
<!-- END TOC -->

## Contributing

When adding or modifying CI jobs:

1. Update relevant documentation in this directory
2. Follow established naming conventions
3. Include clear descriptions of job purpose and requirements
4. Document any dependencies or prerequisites

## Best Practices

- Keep jobs focused and single-purpose
- Minimize job duration and resource usage
- Use appropriate triggers and scheduling
- Maintain clear logging and error handling
