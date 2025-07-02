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

- base on `centos:7.6.1810` official image `WIP`
  > Ref: https://github.com/tikv/tikv/pull/14678
  ```Dockerfile
  FROM centos:7.6.1810

  RUN yum makecache -y && \
      yum install -y epel-release centos-release-scl && \
      yum makecache -y && \
      yum install -y devtoolset-8 perl cmake3 make unzip git which && \
      yum clean all && \
      ln -s /usr/bin/cmake3 /usr/bin/cmake

  # Install protoc
  ENV PROTOC_VER 3.15.8
  RUN curl -LO "https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOC_VER}/protoc-${PROTOC_VER}-linux-x86_64.zip" && \
    unzip protoc-${PROTOC_VER}-linux-x86_64.zip -d /usr/local/ && \
    rm -f protoc-${PROTOC_VER}-linux-x86_64.zip

  # Install Rustup
  RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- --profile complete --default-toolchain none -y

  ######## run test #########
  ### run those commands under tikv repo dir to run unit tests in new terminal to reload your PATH environment variable:
  # cargo install cargo-nextest --locked
  # source /opt/rh/devtoolset-8/enable
  # export LIBRARY_PATH=/usr/local/lib:$LIBRARY_PATH
  # export LD_LIBRARY_PATH=/usr/local/lib:$LD_LIBRARY_PATH
  #
  # LOG_FILE=./target/my_test.log
  # RUSTFLAGS=-Dwarnings FAIL_POINT=1 RUST_BACKTRACE=1 MALLOC_CONF=prof:true,prof_active:false CI=1 make test
  ```

- base on `rust` official image `WIP`, current not passed.
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

- base on `ubuntu` official image `WIP`, current not passed.
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
