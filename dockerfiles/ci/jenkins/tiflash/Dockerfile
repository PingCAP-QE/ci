# base image dockerfile: https://github.com/PingCAP-QE/artifacts/blob/main/dockerfiles/cd/builders/tiflash/Dockerfile
ARG BASE_IMG=ghcr.io/pingcap-qe/cd/builders/tiflash:v2024.10.8-14-g52a7228
FROM $BASE_IMG

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

ENV LANG=en_US.UTF-8
RUN --mount=type=cache,target=/var/cache/dnf \
    dnf install -y xz xz-libs glibc-langpack-en hostname wget procps-ng python27 clang-tools-extra-17.0.6 && \
    ln -s /usr/bin/python2 /usr/bin/python && \
    ln -s /usr/bin/pip2 /usr/bin/pip

RUN --mount=type=cache,target=/var/cache/dnf \
    dnf install -y https://dev.mysql.com/get/mysql80-community-release-el8-1.noarch.rpm && \
    dnf module disable mysql -y && \
    dnf install -y mysql-community-client --nogpgcheck

# install ccache
ARG CCACHE_VERSION=4.10.2
RUN curl -LO  https://github.com/ccache/ccache/releases/download/v${CCACHE_VERSION}/ccache-${CCACHE_VERSION}.tar.gz && \
    tar -xvf ccache-${CCACHE_VERSION}.tar.gz && \
    mkdir -p ccache-${CCACHE_VERSION}/build && \
    cd ccache-${CCACHE_VERSION}/build && \
    cmake .. -DCMAKE_BUILD_TYPE=Release \
    -DZSTD_FROM_INTERNET=ON \
    -DHIREDIS_FROM_INTERNET=ON \
    -DENABLE_TESTING=OFF \
    -DCMAKE_INSTALL_PREFIX="/usr/local" \
    -GNinja && \
    ninja && \
    ninja install && \
    cd ../.. && \
    rm -rf ccache-${CCACHE_VERSION} ccache-${CCACHE_VERSION}.tar.gz && \
    mkdir -p /usr/lib64/ccache && \
    ln -s $(which ccache) /usr/lib64/ccache/clang && \
    ln -s $(which ccache) /usr/lib64/ccache/clang++


ENV CARGO_HOME="/root/.cargo"
# install rustup and set stable toolchain as default
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain nightly-2023-12-28 && \
    source $HOME/.cargo/env && \
    rustup default nightly-2023-12-28

ENV PATH="${CARGO_HOME}/bin:${PATH}"
