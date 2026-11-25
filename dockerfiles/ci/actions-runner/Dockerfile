FROM ghcr.io/actions/actions-runner:2.338.2@sha256:6d0b2d80d80f143f77dcb676e50920100bbb547e39e883454d57f45ab8c31300

LABEL org.opencontainers.image.authors="ci@pingcap.com"
LABEL org.opencontainers.image.description="GitHub Actions runner image for ARC with common build tools"
LABEL org.opencontainers.image.source="https://github.com/PingCAP-QE/ci"

USER root
RUN apt-get update -o APT::Update::Error-Mode=any \
        -o Dir::Etc::sourcelist=sources.list.d/ubuntu.sources \
        -o Dir::Etc::sourceparts=- \
    && apt-get install -y --no-install-recommends \
        coreutils \
        gzip \
        make \
        tar \
        wget \
        xz-utils \
        zip \
    && rm -rf /var/lib/apt/lists/*

USER runner
