FROM hub.pingcap.net/bases/tidb-base:v1.1.0
COPY tidb-server /tidb-server
COPY audit-1.so /plugins/audit-1.so
COPY whitelist-1.so /plugins/whitelist-1.so
EXPOSE 4000
ENTRYPOINT ["/tidb-server"]
