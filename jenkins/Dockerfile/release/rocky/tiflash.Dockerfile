FROM hub.pingcap.net/bases/pingcap_base:v1.0.0
ENV LD_LIBRARY_PATH /tiflash
COPY tiflash /tiflash
ENTRYPOINT ["/tiflash/tiflash", "server"]
