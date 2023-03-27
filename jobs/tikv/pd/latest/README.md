CI Jobs
===

## Run before merged

> The runner requirement: `4` core cpu + `8GB` memory 

| Job name                                                     | Description                                                  | Trigger comment in PR       | CI script                                                    | Can be run locally by contributors | Core Instructions to run locally                             | Runner resouce requirement |
| ------------------------------------------------------------ | ------------------------------------------------------------ | --------------------------- | ------------------------------------------------------------ | ---------------------------------- | ------------------------------------------------------------ | -------------------------- |
| [ghpr_build](./ghpr_build.groovy)                            | lint check and build binary.                                 | `/test build`               | [link](/pipelines/tikv/pd/latest/ghpr_build.groovy)          | yes                                | run `make` and `WITH_RACE=1 make`                            | 4 core cpu, 8GB memory     |
| [pull_integration_copr_test](pull_integration_copr_test.groovy) | A collection of integration tests for the Coprocessor module of TiKV | /test integration-copr-test | [link](/pipelines/tikv/pd/latest/pull_integration_copr_test.groovy) | yes                                | Prepare `tidb-server`, `tikv-server`, `pd-server`, then run `make push-down-test` from [tikv/copr-test](https://github.com/tikv/copr-test) | 4 core cpu,12GB memory     |

Others are refactoring, comming soon.

## Run after merged

> Refactoring, coming soon.