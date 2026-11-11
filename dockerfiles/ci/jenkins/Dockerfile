# base image dockerfile: ../base/Dockerfile
ARG BASE_IMG
FROM $BASE_IMG

# create the user jenkins. The account is created without a password on
# purpose: `useradd` leaves it locked in /etc/shadow, and Jenkins agents connect
# over the agent channel rather than logging into the OS. The account is NOT in
# sudoers: a passwordless `sudo` would hand uid 0 inside the container to the
# pull request's own build scripts, which is what the pod policy's
# runAsUser != 0 rule is meant to prevent.
RUN groupadd -g 1000 jenkins && \
    useradd -u 1000 -g 1000 -m -s /bin/bash jenkins && \
    install -d -o jenkins -g jenkins -m 0755 /var/lib/mysql

# pulsar permission
# linux amd64 only
RUN if [ "$(arch)" = "x86_64" ]; then \
    echo "Detected amd64 architecture, proceeding with installation" && \
    chown -R jenkins:jenkins /usr/local/pulsar; \
    else \
    echo "Architecture not supported: $(arch)"; \
    fi

ENV GOPATH=/go
ENV PATH=$GOPATH/bin:$PATH
RUN mkdir -p /go && chown -R jenkins:jenkins /go

# Switch to the non-root user jenkins and set the working directory
USER jenkins
WORKDIR /home/jenkins

# install gocovmerge
RUN go install github.com/djshow832/gocovmerge@30018881427f1120998f9df5d9228c2ca0a43109

# TODO: we should set it in CI script or pipeline to avoid frequently update the image.
ENV USE_BAZEL_VERSION=6.5.0

# Other environment variables:
# - `BAZELISK_BASE_URL` should be set in CI script or pipeline for different regions.
