FROM python:3.13-slim as base

WORKDIR /app

RUN apt-get update && \
    apt-get install -y curl wget git && \
    rm -rf /var/lib/apt/lists/* && \
    pip install --no-cache-dir requests


# 使用docker官方镜像作为镜像的基础来复制docker客户端，避免直接安装
FROM docker:28.4 as dockercli

FROM base

COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker

# 安装tiup客户端
RUN curl --proto '=https' --tlsv1.2 -sSf https://tiup-mirrors.pingcap.com/install.sh | sh

# 将tiup的可执行文件路径加入到PATH环境变量中
ENV PATH="/root/.tiup/bin:${PATH}"

COPY . .

RUN tiup --version \
  && docker --version \
  && python --version \
  && pip show requests

CMD [ "python" ]
