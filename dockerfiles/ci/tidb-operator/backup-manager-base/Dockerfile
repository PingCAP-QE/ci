# syntax=docker/dockerfile:1
FROM ghcr.io/pingcap-qe/bases/pingcap-base:v1.11.3@sha256:e4763f0b3947c257a9efde18c83d748b56beb5239cf23de37bfeee9a9bbea431

ARG TARGETARCH
RUN --mount=type=cache,target=/var/cache/dnf,sharing=locked \
    dnf install -y ca-certificates bind-utils wget nc unzip

# install rclone tool
ARG RCLONE_VERSION=v1.71.2
RUN curl -fsSL -o /tmp/rclone.zip \
    "https://github.com/rclone/rclone/releases/download/${RCLONE_VERSION}/rclone-${RCLONE_VERSION}-linux-${TARGETARCH}.zip" \
    && unzip -q /tmp/rclone.zip -d /tmp \
    && install -m 0755 "/tmp/rclone-${RCLONE_VERSION}-linux-${TARGETARCH}/rclone" /usr/local/bin/rclone \
    && rm -rf /tmp/rclone.zip "/tmp/rclone-${RCLONE_VERSION}-linux-${TARGETARCH}"

# install shush tool
ARG SHUSH_VERSION=v1.5.5
RUN curl -fsSL -o /usr/local/bin/shush \
    https://github.com/realestate-com-au/shush/releases/download/${SHUSH_VERSION}/shush_linux_${TARGETARCH} \
    && chmod +x /usr/local/bin/shush
