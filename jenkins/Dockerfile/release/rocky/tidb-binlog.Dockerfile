FROM hub.pingcap.net/bases/tools-base:v1.0.0
COPY pump /pump
COPY drainer /drainer
COPY reparo /reparo
COPY binlogctl /binlogctl
EXPOSE 4000 8249 8250
CMD ["/pump"]
