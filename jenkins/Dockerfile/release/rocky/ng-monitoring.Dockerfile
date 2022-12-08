FROM hub.pingcap.net/bases/pingcap_base:v1.0.0
COPY ng-monitoring-server /ng-monitoring-server
EXPOSE 12020
ENTRYPOINT ["/ng-monitoring-server"]
