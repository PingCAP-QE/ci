FROM hub.pingcap.net/tiflash/centos:7.9.2009-amd64

USER root
WORKDIR /root/

ARG INSTALL_MYSQL=0
ENV HOME /root/
ENV TZ Asia/Shanghai
ENV LD_LIBRARY_PATH /tiflash

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo $TZ > /etc/timezone && \
    if [[ $INSTALL_MYSQL -eq 1 ]]; then yum install mysql -y; yum clean all; fi


COPY tiflash /tiflash

ENTRYPOINT ["/tiflash/tiflash", "server"]
