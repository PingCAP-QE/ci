# Base image
FROM quay.io/rockylinux/rockylinux:8.10.20240528

LABEL org.opencontainers.image.authors="ci@pingcap.com"
LABEL org.opencontainers.image.description="CI Base image for testing"
LABEL org.opencontainers.image.source="https://github.com/PingCAP-QE/ci"

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# install OS packages.
RUN --mount=type=cache,target=/var/cache/dnf \
    dnf upgrade-minimal -y && \
    dnf install -y make git gcc wget unzip psmisc lsof jq python27 python3.12 python3.12-pip nc ncurses-compat-libs && \
    ln -s /usr/bin/python2 /usr/bin/python && \
    ln -s /usr/bin/pip2 /usr/bin/pip && \
    pip install s3cmd==2.3.0 requests==2.26.0 certifi==2021.10.8 && \
    pip3 install requests==2.27.1

# Install MySQL 5.7
# linux amd64 only
RUN if [ "$(arch)" = "x86_64" ]; then \
    echo "Detected amd64 architecture, proceeding with installation" && \
    dnf module disable mysql -y && \
    echo '[mysql57-community]' > /etc/yum.repos.d/mysql57.repo && \
    echo 'name=MySQL 5.7 Community Server' >> /etc/yum.repos.d/mysql57.repo && \
    echo 'baseurl=http://repo.mysql.com/yum/mysql-5.7-community/el/7/$basearch/' >> /etc/yum.repos.d/mysql57.repo && \
    echo 'enabled=1' >> /etc/yum.repos.d/mysql57.repo && \
    echo 'gpgcheck=0' >> /etc/yum.repos.d/mysql57.repo && \
    dnf install -y mysql-community-server && \
    dnf clean all && \
    rm -rf /var/cache/dnf; \
    else \
    echo "Architecture not supported: $(arch)"; \
    fi

# Install gbk support
RUN --mount=type=cache,target=/var/cache/dnf \
    dnf install -y glibc-locale-source && \
    localedef -f GBK -i zh_CN zh_CN.gbk

# install golang toolchain
# renovate: datasource=docker depName=golang
ARG GOLANG_VERSION=1.25.10
RUN OS=linux; ARCH=$([ "$(arch)" = "x86_64" ] && echo amd64 || echo arm64); \
    curl -fsSL https://dl.google.com/go/go${GOLANG_VERSION}.linux-${ARCH}.tar.gz | tar -C /usr/local -xz
ENV PATH=/usr/local/go/bin/:$PATH
LABEL go-version="${GOLANG_VERSION}"

# install rust toolchain
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s - -y --default-toolchain nightly
ENV PATH=/root/.cargo/bin:$PATH

# install nodejs toolchain
ARG NODE_VERSION=18
RUN curl -fsSL https://rpm.nodesource.com/setup_${NODE_VERSION}.x | bash - \
    && dnf install -y nsolid \
    && npm install -g yarn@1.22.22 pnpm@8.15.9

#### install java tool chains: open-jdk, gradle, apache-maven
#   -> open-jdk
ARG JAVA_VER=17
RUN --mount=type=cache,target=/var/cache/dnf \
    dnf install -y java-${JAVA_VER}-openjdk-devel
ENV JAVA_HOME=/usr/lib/jvm/jre-openjdk

#   -> gradle
ARG GRADLE_VER=7.4.2
RUN wget https://services.gradle.org/distributions/gradle-${GRADLE_VER}-bin.zip && \
    unzip gradle-${GRADLE_VER}-bin.zip -d /opt && \
    rm gradle-${GRADLE_VER}-bin.zip
ENV PATH=$PATH:/opt/gradle-${GRADLE_VER}/bin

#   -> apache-maven
ARG MAVEN_VER=3.8.9
RUN wget https://archive.apache.org/dist/maven/maven-3/${MAVEN_VER}/binaries/apache-maven-${MAVEN_VER}-bin.tar.gz && \
    tar -xf apache-maven-${MAVEN_VER}-bin.tar.gz -C /opt && \
    rm apache-maven-${MAVEN_VER}-bin.tar.gz
ENV PATH=$PATH:/opt/apache-maven-${MAVEN_VER}/bin

#### install tools: bazelisk, codecov, oras
# renovate: datasource=github-tags depName=bazelbuild/bazelisk
ARG BAZELISK_VERSION=v1.28.1
RUN OS=linux; ARCH=$([ "$(arch)" = "x86_64" ] && echo amd64 || echo arm64); \
    curl -fsSL "https://github.com/bazelbuild/bazelisk/releases/download/${BAZELISK_VERSION}/bazelisk-${OS}-${ARCH}" -o /usr/local/bin/bazel && chmod +x /usr/local/bin/bazel

# codecov tool
# renovate: datasource=github-tags depName=codecov/uploader
ARG CODECOV_VERSION=v0.8.0
RUN folder=$([ "$(arch)" = "x86_64" ] && echo linux || echo aarch64); \
    curl -fsSL https://uploader.codecov.io/${CODECOV_VERSION}/${folder}/codecov -o /usr/local/bin/codecov && \
    chmod +x /usr/local/bin/codecov

# oras tool
COPY --from=ghcr.io/oras-project/oras:v1.3.2 /bin/oras /usr/local/bin/oras

# gh-ost
# linux amd64 only
RUN if [ "$(arch)" = "x86_64" ]; then \
    echo "Detected amd64 architecture, proceeding with installation" && \
    wget https://github.com/github/gh-ost/releases/download/v1.0.47/gh-ost-binary-linux-20181016015113.tar.gz && \
    tar -xf gh-ost-binary-linux-20181016015113.tar.gz -C /usr/local/bin/ && \
    rm gh-ost-binary-linux-20181016015113.tar.gz; \
    else \
    echo "Architecture not supported: $(arch)"; \
    fi

# pulsar
# linux amd64 only
RUN if [ "$(arch)" = "x86_64" ]; then \
    echo "Detected amd64 architecture, proceeding with installation" && \
    curl https://archive.apache.org/dist/pulsar/pulsar-3.2.0/apache-pulsar-3.2.0-bin.tar.gz -o pulsar.tar.gz && \
    tar -xvf pulsar.tar.gz && \
    mv apache-pulsar-3.2.0 /usr/local/pulsar && \
    rm pulsar.tar.gz; \
    else \
    echo "Architecture not supported: $(arch)"; \
    fi
ENV PATH=$PATH:/usr/local/pulsar/bin

# install percona-toolkit
# linux amd64 only
RUN if [ "$(arch)" = "x86_64" ]; then \
    echo "Detected amd64 architecture, proceeding with installation" && \
    dnf install -y https://repo.percona.com/testing/centos/7/RPMS/x86_64/percona-toolkit-3.0.12-1.el7.x86_64.rpm; \
    else \
    echo "Architecture not supported: $(arch)"; \
    fi

# Add Tini
# renovate: datasource=github-tags depName=krallin/tini
ARG TINI_VERSION=v0.19.0
RUN ARCH=$([ "$(arch)" = "x86_64" ] && echo amd64 || echo arm64); \
    curl -fsSL -o /usr/local/bin/tini https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini-${ARCH} && \
    chmod +x /usr/local/bin/tini

# Add docker cli
COPY --from=docker.io/docker:28.5.2-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker.io/docker:28.5.2-cli /usr/local/libexec/docker/cli-plugins /usr/local/libexec/docker/cli-plugins
RUN ln -sf /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/bin/docker-compose && \
    ln -sf /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/bin/docker-buildx
