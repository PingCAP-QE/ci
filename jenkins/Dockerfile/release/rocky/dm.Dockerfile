FROM hub.pingcap.net/bases/tools-base:v1.1.0
COPY dm-worker /dm-worker
COPY dm-master /dm-master
COPY dmctl /dmctl

EXPOSE 8291 8261 8262
