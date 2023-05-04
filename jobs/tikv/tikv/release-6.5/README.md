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

- base on `rust` official image `WIP`
    ```Dockerfile
    # Base image, Rust version might be varied from releases, please check the Cargo.toml before setting the correct version.
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
    # rustup toolchain install `cat rust-toolchain`
    # RUSTFLAGS=-Dwarnings FAIL_POINT=1 RUST_BACKTRACE=1 MALLOC_CONF=prof:true,prof_active:false CI=1 make test_with_nextest
    ```

- base on `ubuntu` office image `WIP`
  ```Dockerfile
  FROM ubuntu:22

  # install build essential
  RUN apt-get update && \
    apt-get install -y build-essential cmake llvm protobuf-compiler curl git python3 && \
    apt-get clean
  RUN update-alternatives --install /usr/bin/python python /usr/bin/python3 1

  # install rust toolchain with nightly and complete profile
  RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
  
  ######### run test for TiKV ########
  # git clone https://github.com/tikv/tikv.git --branch release-6.5
  
  ### Run unit tests(you can split the job by adjust the params):
  # cd tikv
  # rustup toolchain install `cat rust-toolchain`
  # LOG_FILE=./target/my_test.log
  # RUSTFLAGS=-Dwarnings FAIL_POINT=1 RUST_BACKTRACE=1 MALLOC_CONF=prof:true,prof_active:false CI=1 make test_with_nextest
  ```
## Run after merged

Currently no jobs run after pull request be merged.
