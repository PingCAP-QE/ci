FROM hub.pingcap.net/bases/tidb-base:v1.0.0
COPY tidb-server /tidb-server
EXPOSE 4000
ENTRYPOINT ["/tidb-server"]
