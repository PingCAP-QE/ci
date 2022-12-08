FROM hub.pingcap.net/jenkins/pingcap_base:v1.0.0
COPY cdc /cdc
EXPOSE 8300

CMD ["/cdc"]
