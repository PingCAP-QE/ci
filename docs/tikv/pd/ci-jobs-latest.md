CI Jobs
===

## Run before merged

> The runner requirement: `4` core cpu + `12GB` memory.
> Recommend run in [container](#dev-containers) to prepare the tools that we needed.


| Job name                                                                           | Description                                                          | Trigger comment in PR         | CI script                                                             | Can be run locally by contributors | Core Instructions to run locally                                                                                                           | Runner resouce requirement |
| ---------------------------------------------------------------------------------- | -------------------------------------------------------------------- | ----------------------------- | --------------------------------------------------------------------- | ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------- |
| [ghpr_build](./ghpr_build.groovy)                                                  | lint check and build binary.                                         | `/test build`                 | [link](/pipelines/tikv/pd/latest/ghpr_build.groovy)                   | yes                                | run `make` and `WITH_RACE=1 make`                                                                                                          | 4 core cpu, 8GB memory     |
| [pull_integration_copr_test](pull_integration_copr_test.groovy)                    | A collection of integration tests for the Coprocessor module of TiKV | `/test integration-copr-test` | [link](/pipelines/tikv/pd/latest/pull_integration_copr_test.groovy)   | yes                                | Prepare `tidb-server`, `tikv-server`, `pd-server`, then run `make push-down-test` from [tikv/copr-test](https://github.com/tikv/copr-test) | 4 core cpu,12GB memory     |
| [Other github workflows](https://github.com/tikv/pd/tree/master/.github/workflows) | Unit test, static checks and function tests                          | N/A                           | [link](https://github.com/tikv/pd/tree/release-6.5/.github/workflows) | yes                                | Please see the steps in Github workflows scripts                                                                                           | 4 core cpu, 8GB memory     |

Others are refactoring, comming soon.

### Dev containers

- base on `golang` office image
    ```Dockerfile
    # Base image
    FROM golang:1.19

    # install build essential
    RUN apt-get update && \
        apt-get install -y build-essential unzip psmisc && \
        apt-get clean

    ######### run test for PD ########
    # git clone https://github.com/tikv/pd.git --branch master

    # Run unit tests(you can split the job by adjust the params):
    # cd pd && make ci-test-job JOB_COUNT=1 JOB_INDEX=1 && make ci-test-job-submod
    ```

## Run after merged

> Refactoring, coming soon.
