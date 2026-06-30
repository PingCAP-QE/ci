# syntax=docker/dockerfile:1
FROM debian:bookworm-slim@sha256:60eac759739651111db372c07be67863818726f754804b8707c90979bda511df

ARG TARGETARCH

RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt/lists,sharing=locked \
    apt-get update && \
    apt-get install -y ca-certificates curl git openssl default-mysql-client unzip python3

# install kubectl, helm CLIs
ARG KUBECTL_VERSION=v1.28.5
ARG HELM_VERSION=v3.11.0
RUN curl -fsSL "https://storage.googleapis.com/kubernetes-release/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl" -o /usr/local/bin/kubectl \
    && chmod +x /usr/local/bin/kubectl \
    && curl -fsSL "https://get.helm.sh/helm-${HELM_VERSION}-linux-${TARGETARCH}.tar.gz" | tar xz -C /usr/local/bin --strip-components=1 linux-${TARGETARCH}/helm

# install AWS CLI
RUN set -eux; \
    case "${TARGETARCH}" in \
    amd64) aws_arch=x86_64 ;; \
    arm64) aws_arch=aarch64 ;; \
    *) echo "unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-${aws_arch}.zip" -o awscliv2.zip; \
    unzip awscliv2.zip; \
    ./aws/install; \
    rm -rf aws awscliv2.zip

ARG CERT_MANAGER_VERSION=v1.15.1
ADD https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml /cert-manager.yaml
