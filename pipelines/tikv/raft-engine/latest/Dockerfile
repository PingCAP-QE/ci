# docker build -t hub.pingcap.net/jenkins/tikv-raft-engine-cached:toolchain-stable .
FROM ubuntu:24.04

# install rustup and some tools
RUN apt-get update && apt-get install -y curl build-essential

RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y

ENV PATH="/root/.cargo/bin:${PATH}"

RUN rustup toolchain install stable && \
    rustup default stable && \
    rustup component add rustfmt clippy rust-src llvm-tools-preview && \
    cargo install grcov --version 0.4.3

ENV PATH="/root/.cargo/bin:${PATH}"
