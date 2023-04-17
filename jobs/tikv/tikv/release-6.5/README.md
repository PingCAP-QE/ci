CI Jobs
===

## Run before merged

> The runner requirement: `8` core cpu + `16GB` memory.
> Recommend run in [container](#dev-containers) to prepare the tools that we needed.


Current they will be refactored soon:

| Job name                                                      | Description                  | Trigger comment in PR | CI script                                                | Can be run locally by contributors | Core Instructions to run locally                       | Runner resouce requirement |
| ------------------------------------------------------------- | ---------------------------- | --------------------- | -------------------------------------------------------- | ---------------------------------- | ------------------------------------------------------ | -------------------------- |
| [tikv_ghpr_test](/jenkins/jobs/ci/tikv/tikv/ghpr_test.groovy) | lint check and build binary. | `/test build`         | [link](/jenkins/pipelines/ci/tikv/tikv_ghpr_test.groovy) | yes                                | `FAIL_POINT=1=1 make test_with_nextest -j <cpu-cores>` | 8 core cpu, 16GB memory    |

More will be added.

### Dev containers

> Currently we are not refactored the jobs, and the CI images are pulished on private registry.

- base on `rust` office image `WIP`
    ```Dockerfile
    # Base image, Go version might be varied from releases, please check the go module setting before setting the correct version.
    FROM rust:1.68

    # install build essential
    RUN apt-get update && \
        apt-get install -y build-essential cmake llvm protobuf-compiler python3 && \
        apt-get clean
    RUN update-alternatives --install /usr/bin/python python /usr/bin/python3 1

    # install nextest tool.
    RUN cargo install cargo-nextest --locked

    ######### run test for TiKV ########

    # git clone https://github.com/tikv/tikv.git --branch release-6.5  

    # Run unit tests(you can split the job by adjust the params):
    # cd tikv
    # LOG_FILE=./target/my_test.log
    # RUSTFLAGS=-Dwarnings FAIL_POINT=1 RUST_BACKTRACE=1 MALLOC_CONF=prof:true,prof_active:false CI=1 make test_with_nextest
    ```

## Run after merged

Currently no jobs run after pull request be merged.
