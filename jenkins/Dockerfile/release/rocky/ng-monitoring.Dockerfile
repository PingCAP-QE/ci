FROM hub.pingcap.net/bases/pingcap-base:v1.1.0
COPY ng-monitoring-server /ng-monitoring-server
EXPOSE 12020
ENTRYPOINT ["/ng-monitoring-server"]
