FROM hub.pingcap.net/bases/pingcap-base:v1.0.0
COPY dm-worker /dm-worker
COPY dm-master /dm-master
COPY dmctl /dmctl

EXPOSE 8291 8261 8262
