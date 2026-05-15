FROM pandoc/extra@sha256:07f33f3c77fdde0eede08e2e145de82a048f35ff3e642546cb6e85da9b414498

ARG RCLONE_VERSION=1.71.1
ARG TARGETARCH

RUN set -eux; \
    export DEBIAN_FRONTEND=noninteractive; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl fonts-wqy-microhei; \
    arch="${TARGETARCH:-$(dpkg --print-architecture)}"; \
    case "${arch}" in \
      amd64|x86_64) rclone_arch=amd64 ;; \
      arm64|aarch64) rclone_arch=arm64 ;; \
      *) echo "unsupported arch: ${arch}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://downloads.rclone.org/v${RCLONE_VERSION}/rclone-v${RCLONE_VERSION}-linux-${rclone_arch}.zip" -o /tmp/rclone.zip; \
    apt-get install -y --no-install-recommends unzip; \
    unzip /tmp/rclone.zip -d /tmp; \
    install -m 0755 "/tmp/rclone-v${RCLONE_VERSION}-linux-${rclone_arch}/rclone" /usr/local/bin/rclone; \
    rm -rf /tmp/rclone* /var/lib/apt/lists/*; \
    fc-cache -fv >/dev/null || true

ENTRYPOINT ["/bin/bash", "-lc"]
