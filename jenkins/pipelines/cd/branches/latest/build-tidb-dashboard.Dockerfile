FROM hub.pingcap.net/bases/pingcap_base:v1 as builder
ARG TARGETARCH
RUN sed -e 's|^mirrorlist=|#mirrorlist=|g' \
        -e 's|^#baseurl=http://dl.rockylinux.org/$contentdir|baseurl=https://mirrors.sjtug.sjtu.edu.cn/rocky|g' \
        -i.bak \
        /etc/yum.repos.d/rocky-extras.repo \
        /etc/yum.repos.d/rocky.repo \
    && dnf makecache
RUN dnf install -y\
    make \
    git \
    bash \
    curl \
    findutils \
    gcc \
    glibc-devel \
    nodejs \
    npm \
    java-11-openjdk-devel &&  \
    dnf clean all
RUN curl -o /tmp/go.tar.gz https://dl.google.com/go/go1.18.8.linux-${TARGETARCH}.tar.gz &&\
     tar -xzf /tmp/go.tar.gz -C /usr/local && ln -s /usr/local/go/bin/go /usr/local/bin/go &&\
     rm /tmp/go.tar.gz

RUN npm install -g pnpm

RUN mkdir -p tidb-dashboard/ui
WORKDIR tidb-dashboard

# Cache go module dependencies.
COPY go.mod .
COPY go.sum .
RUN GO111MODULE=on go mod download

# Cache npm dependencies.
WORKDIR tidb-dashboard/ui
COPY ui/pnpm-lock.yaml .
RUN pnpm fetch

# Build.
WORKDIR tidb-dashboard
COPY . .
RUN make package PNPM_INSTALL_TAGS=--offline

FROM hub.pingcap.net/bases/pingcap_base:v1.0.0

COPY --from=builder bin/tidb-dashboard /tidb-dashboard

EXPOSE 12333

ENTRYPOINT ["/tidb-dashboard"]
