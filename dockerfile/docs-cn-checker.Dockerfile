FROM debian:jessie

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl wget && rm -rf /var/lib/apt/lists/*
RUN set -ex; if ! command -v gpg > /dev/null; then apt-get update; apt-get install -y --no-install-recommends gnupg2 dirmngr ; rm -rf /var/lib/apt/lists/*; fi
RUN apt-get update && apt-get install -y --no-install-recommends bzr git mercurial openssh-client subversion procps && rm -rf /var/lib/apt/lists/*
RUN set -ex; apt-get update; apt-get install -y --no-install-recommends autoconf automake bzip2 dpkg-dev file g++ gcc imagemagick libbz2-dev libc6-dev libcurl4-openssl-dev libdb-dev libevent-dev libffi-dev libgdbm-dev libgeoip-dev libglib2.0-dev libjpeg-dev libkrb5-dev liblzma-dev libmagickcore-dev libmagickwand-dev libncurses-dev libpng-dev libpq-dev libreadline-dev libsqlite3-dev libssl-dev libtool libwebp-dev libxml2-dev libxslt-dev libyaml-dev make patch xz-utils zlib1g-dev $( if apt-cache show 'default-libmysqlclient-dev' 2>/dev/null | grep -q '^Version:'; then echo 'default-libmysqlclient-dev'; else echo 'libmysqlclient-dev'; fi ) ; rm -rf /var/lib/apt/lists/*

ENV PATH=/usr/local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
ENV LANG=C.UTF-8
RUN apt-get update && apt-get install -y --no-install-recommends tcl tk && rm -rf /var/lib/apt/lists/*

ENV GPG_KEY=0D96DF4D4110E5C43FBFB17F2D347EA6AA65421D
ENV PYTHON_VERSION=3.6.2

RUN set -ex && buildDeps=' dpkg-dev tcl-dev tk-dev ' && apt-get update && apt-get install -y $buildDeps --no-install-recommends && rm -rf /var/lib/apt/lists/* && wget -O python.tar.xz "https://www.python.org/ftp/python/${PYTHON_VERSION%%[a-z]*}/Python-$PYTHON_VERSION.tar.xz" && wget -O python.tar.xz.asc "https://www.python.org/ftp/python/${PYTHON_VERSION%%[a-z]*}/Python-$PYTHON_VERSION.tar.xz.asc" && export GNUPGHOME="$(mktemp -d)" && gpg --keyserver ha.pool.sks-keyservers.net --recv-keys "$GPG_KEY" && gpg --batch --verify python.tar.xz.asc python.tar.xz && rm -rf "$GNUPGHOME" python.tar.xz.asc && mkdir -p /usr/src/python && tar -xJC /usr/src/python --strip-components=1 -f python.tar.xz && rm python.tar.xz && cd /usr/src/python && gnuArch="$(dpkg-architecture --query DEB_BUILD_GNU_TYPE)" && ./configure --build="$gnuArch" --enable-loadable-sqlite-extensions --enable-shared --with-system-expat --with-system-ffi --without-ensurepip && make -j "$(nproc)" && make install && ldconfig && apt-get purge -y --auto-remove $buildDeps && find /usr/local -depth \( \( -type d -a \( -name test -o -name tests \) \) -o \( -type f -a \( -name '*.pyc' -o -name '*.pyo' \) \) \) -exec rm -rf '{}' + && rm -rf /usr/src/python
RUN cd /usr/local/bin && ln -s idle3 idle && ln -s pydoc3 pydoc && ln -s python3 python && ln -s python3-config python-config
ENV PYTHON_PIP_VERSION=9.0.1
RUN set -ex; wget -O get-pip.py 'https://bootstrap.pypa.io/get-pip.py'; python get-pip.py --disable-pip-version-check --no-cache-dir "pip==$PYTHON_PIP_VERSION" ; pip --version; find /usr/local -depth \( \( -type d -a \( -name test -o -name tests \) \) -o \( -type f -a \( -name '*.pyc' -o -name '*.pyo' \) \) \) -exec rm -rf '{}' +; rm -f get-pip.py
CMD ["python3"]

RUN echo 'APT::Get::Assume-Yes "true";' > /etc/apt/apt.conf.d/90circleci && echo 'DPkg::Options "--force-confnew";' >> /etc/apt/apt.conf.d/90circleci
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y git mercurial xvfb locales sudo openssh-client ca-certificates tar gzip parallel net-tools netcat unzip zip bzip2
RUN ln -sf /usr/share/zoneinfo/Etc/UTC /etc/localtime
RUN locale-gen C.UTF-8 || true
ENV LANG=C.UTF-8
RUN JQ_URL="https://circle-downloads.s3.amazonaws.com/circleci-images/cache/linux-amd64/jq-latest" && curl --silent --show-error --location --fail --retry 3 --output /usr/bin/jq $JQ_URL && chmod +x /usr/bin/jq && jq --version
RUN set -ex && export DOCKER_VERSION=$(curl --silent --fail --retry 3 https://download.docker.com/linux/static/stable/x86_64/ | grep -o -e 'docker-[.0-9]*-ce\.tgz' | sort -r | head -n 1) && DOCKER_URL="https://download.docker.com/linux/static/stable/x86_64/${DOCKER_VERSION}" && echo Docker URL: $DOCKER_URL && curl --silent --show-error --location --fail --retry 3 --output /tmp/docker.tgz "${DOCKER_URL}" && ls -lha /tmp/docker.tgz && tar -xz -C /tmp -f /tmp/docker.tgz && mv /tmp/docker/* /usr/bin && rm -rf /tmp/docker /tmp/docker.tgz && which docker && (docker version || true)
RUN COMPOSE_URL="https://circle-downloads.s3.amazonaws.com/circleci-images/cache/linux-amd64/docker-compose-latest" && curl --silent --show-error --location --fail --retry 3 --output /usr/bin/docker-compose $COMPOSE_URL && chmod +x /usr/bin/docker-compose && docker-compose version
RUN DOCKERIZE_URL="https://circle-downloads.s3.amazonaws.com/circleci-images/cache/linux-amd64/dockerize-latest.tar.gz" && curl --silent --show-error --location --fail --retry 3 --output /tmp/dockerize-linux-amd64.tar.gz $DOCKERIZE_URL && tar -C /usr/local/bin -xzvf /tmp/dockerize-linux-amd64.tar.gz && rm -rf /tmp/dockerize-linux-amd64.tar.gz && dockerize --version

RUN groupadd --gid 3434 circleci && useradd --uid 3434 --gid circleci --shell /bin/bash --create-home circleci && echo 'circleci ALL=NOPASSWD: ALL' >> /etc/sudoers.d/50-circleci && echo 'Defaults env_keep += "DEBIAN_FRONTEND"' >> /etc/sudoers.d/env_keep
USER [circleci]
CMD ["/bin/sh"]

RUN mkdir -p /tmp/prepare && cd /tmp/prepare && curl -L https://github.com/jgm/pandoc/releases/download/1.19.2/pandoc-1.19.2-1-amd64.deb -o pandoc.deb && sudo dpkg -i pandoc.deb && sudo apt-get -y install texlive-xetex texlive-latex-extra texlive-lang-cjk etoolbox && sudo apt-get -y install ttf-wqy-microhei && sudo pip3 install qiniu && sudo rm -rf /tmp/prepare && sudo rm -rf /var/lib/apt/lists/* && sudo bash -c 'echo "119.188.128.5 uc.qbox.me" >> /etc/hosts'
USER root
