FROM hub.pingcap.net/bases/pingcap-base:v1
RUN curl --proto '=https' --tlsv1.2 -sSf https://tiup-mirrors.pingcap.com/install.sh | sh
ENV PATH=$PATH:/root/.tiup/bin
COPY --from=bitnami/oras:1.1.0-debian-11-r14 /oras /bin/oras
COPY --chmod=755 check_artifact.sh /bin/check_artiact.sh
