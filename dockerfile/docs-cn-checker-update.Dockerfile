# docker build . -t hub.pingcap.net/jenkins/docs-cn-checker:v1.0.0
FROM python:3.9-bookworm

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl wget git sudo locales build-essential \
    && rm -rf /var/lib/apt/lists/*

RUN ln -sf /usr/share/zoneinfo/Etc/UTC /etc/localtime
RUN locale-gen C.UTF-8 || true
ENV LANG=C.UTF-8

RUN pip3 install awscli==1.32.107 boto3==1.34.107 qiniu==7.13.1

# package etoolbox is not found in debian:bookworm
RUN mkdir -p /tmp/prepare \
    && cd /tmp/prepare \
    && curl -L https://github.com/jgm/pandoc/releases/download/1.19.2/pandoc-1.19.2-1-amd64.deb -o pandoc.deb \
    && dpkg -i pandoc.deb \
    && apt-get update \
    && apt-get -y install texlive-xetex texlive-latex-extra texlive-lang-cjk \
    && apt-get -y install ttf-wqy-microhei \
    && rm -rf /tmp/prepare \
    && rm -rf /var/lib/apt/lists/*

# Unclear about the background of this setting, so comment and keep it for now.
# RUN sudo bash -c 'echo "119.188.128.5 uc.qbox.me" >> /etc/hosts'

