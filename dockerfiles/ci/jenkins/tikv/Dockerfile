# base image dockerfile: https://github.com/PingCAP-QE/artifacts/blob/main/dockerfiles/cd/builders/tikv/Dockerfile
ARG BASE_IMG=ghcr.io/pingcap-qe/cd/builders/tikv:v2026.3.22-3-g892e449
FROM $BASE_IMG

# create user jenkins and add it to sudoers.
RUN --mount=type=cache,target=/var/cache/dnf \
    dnf install -y sudo
RUN groupadd -g 1000 jenkins && \
    useradd -u 1000 -g 1000 -m -s /bin/bash jenkins && \
    echo "jenkins:password" | chpasswd && \
    echo "jenkins ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# install python2.7 and other dependencies
RUN --mount=type=cache,target=/var/cache/dnf \
    dnf install -y python27 hostname wget procps-ng
RUN ln -s /usr/bin/python2 /usr/bin/python && \
    ln -s /usr/bin/pip2 /usr/bin/pip


# Switch to the non-root user jenkins and set the working directory
USER jenkins
WORKDIR /home/jenkins

ENV CARGO_HOME="/home/jenkins/.cargo"
# install rustup and set stable toolchain as default
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain nightly-2023-12-28 && \
    source $HOME/.cargo/env && \
    rustup default nightly-2023-12-28

ENV PATH="${CARGO_HOME}/bin:${PATH}"

# install components
RUN rustup component add rustfmt clippy rust-src llvm-tools && \
    rustup set profile minimal

# Install specific versions of cargo-nextest and cargo-sort
RUN rustup update stable && rustup default stable && \
    cargo install --locked --version 0.9.72 cargo-nextest  && \
    cargo install --locked --version 1.0.9 cargo-sort

ENV CARGO_INCREMENTAL=0
