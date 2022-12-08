FROM hub.pingcap.net/bases/pd_base:v1.0.0
COPY bin/pd-server /pd-server
COPY bin/pd-ctl /pd-ctl
EXPOSE 2379 2380
ENTRYPOINT ["/pd-server"]
