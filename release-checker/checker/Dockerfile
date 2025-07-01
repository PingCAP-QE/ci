FROM docker:stable

RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.tuna.tsinghua.edu.cn/g' /etc/apk/repositories

RUN apk add --update --no-cache python3 bash curl gcc && ln -sf python3 /usr/bin/python
RUN python3 -m ensurepip

ADD pkg /release-checker/pkg
ADD main.py /release-checker
ADD requirements.txt /release-checker
RUN chmod -R 777 /release-checker

RUN pip3 install -r /release-checker/requirements.txt

COPY sgerrand.rsa.pub /etc/apk/keys/sgerrand.rsa.pub
COPY glibc-2.32-r0.apk /
RUN  apk add /glibc-2.32-r0.apk
