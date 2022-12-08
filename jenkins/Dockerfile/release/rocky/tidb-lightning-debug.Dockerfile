FROM hub.pingcap.net/bases/pingcap_base:v1.0.0
COPY tidb-lightning /tidb-lightning
COPY tidb-lightning-ctl /tidb-lightning-ctl
COPY br /br
