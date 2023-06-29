FROM hub.pingcap.net/jenkins/centos7_golang-1.16

USER root
WORKDIR /root

RUN curl -fsSL https://rpm.nodesource.com/setup_16.x | bash - \
    && yum install -y nodejs \
    && yum clean all

RUN npm install -g pnpm@7.13.6
RUN npm install -g yarn

USER jenkins
WORKDIR /home/jenkins
