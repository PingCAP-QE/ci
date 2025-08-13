FROM hub.pingcap.net/bases/pingcap-base:v1
RUN curl --proto '=https' --tlsv1.2 -sSf https://tiup-mirrors.pingcap.com/install.sh | sh
ENV PATH=$PATH:/root/.tiup/bin
COPY --from=bitnami/oras:1.2.3-debian-12-r7 /oras /bin/oras
COPY --chmod=755 check_artifact.sh /bin/check_artiact.sh
