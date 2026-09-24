CI Jobs
===

## Run before merged

- Host is `Linux` & `X86_64`, `arm64` is WIP.
- **Disabled cgroup v2 on the host**, there is a [reference](#how-to-check-cgroup-and-disable-cgroup2).
  - Currently some local tests are depended on cgroup.
- The runner requirement: `64` core cpu + `128GB` memory with golang `v1.20.10` installed
  - Tools and setting please refer to [container env](#dev-containers), you can setup in you bare hosts.
- Run as `root` user.

| Job name                                                                 | Description                               | Trigger comment in PR | CI script                                                          | Can be run locally by contributors | Core Instructions to run locally                                                                                                                                                                                                                                                                    |
| ------------------------------------------------------------------------ | ----------------------------------------- | --------------------- | ------------------------------------------------------------------ | ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [ghpr_build](/jenkins/jobs/pingcap/tidb/release-7.1/ghpr_build/dsl.groovy)           | lint check and build binary.              | `/test build`         | [link](/jenkins/jobs/pingcap/tidb/release-7.1/ghpr_build/Jenkinsfile)      | yes                                | run `make bazel_build`                                                                                                                                                                                                                                                                              |
| [ghpr-unit-test](/jenkins/jobs/pingcap/tidb/release-7.1/ghpr_unit_test/dsl.groovy)   | Unit/Func tests                           | `/test unit-test`     | [link](/jenkins/jobs/pingcap/tidb/release-7.1/ghpr_unit_test/Jenkinsfile)  | yes                                | run `make bazel_coverage_test`                                                                                                                                                                                                                                                                      | yes |
| [ghpr-check-dev](/jenkins/jobs/pingcap/tidb/release-7.1/ghpr_check/dsl.groovy)       | More static checks.                       | `/test check-dev`     | [link](/jenkins/jobs/pingcap/tidb/release-7.1/ghpr_check/Jenkinsfile)      | yes                                | run `make gogenerate check explaintest`                                                                                                                                                                                                                                                             |
| [ghpr-check-dev2](/jenkins/jobs/pingcap/tidb/release-7.1/ghpr_check2/dsl.groovy)     | Basic function tests                      | `/test check-dev2`    | [link](/jenkins/jobs/pingcap/tidb/release-7.1/ghpr_check2/Jenkinsfile)     | yes                                | Add component binaries `tikv-server` and `pd-server` to the `bin/` dir after `make bazel_build`, then run the scripts in `scripts/pingcap/tidb` folder of  `pingcap-qe/ci` repo, [detail](https://github.com/PingCAP-QE/ci/blob/main/jenkins/jobs/pingcap/tidb/release-7.1/ghpr_check2/Jenkinsfile#L79-L86) |
| [ghpr-mysql-test](/jenkins/jobs/pingcap/tidb/release-7.1/ghpr_mysql_test/dsl.groovy) | Test for compatibility for mysql protocol | `/test mysql-test`    | [link](/jenkins/jobs/pingcap/tidb/release-7.1/ghpr_mysql_test/Jenkinsfile) | no                                 | 🔒test repo(PingCAP-QE/tidb-test) not public                                                                                                                                                                                                                                                            |


### Tips

#### How to check cgroup and disable cgroup2

```bash
############## check cgroup ##############
##### If your system supports cgroupv2, you would see:
# nodev   cgroup
# nodev   cgroup2

##### On a system with only cgroupv1, you would only see:
# nodev   cgroup
grep cgroup /proc/filesystems

############# disable cgroup2 in grub2 boot menu ##################
sed -i '/^GRUB_CMDLINE_LINUX/ s/"$/ systemd.unified_cgroup_hierarchy=0"/' /etc/default/grub
grub2-mkconfig -o /boot/grub2/grub.cfg
# after that, please reboot the host.
```

### Dev containers

- base on `golang` office image
    ```Dockerfile
    # Base image
    FROM golang:1.20

    # install build essential
    RUN apt-get update && \
        apt-get install -y build-essential unzip psmisc && \
        apt-get clean

    # install bazel tool
    ENV ARCH amd64
    RUN curl -fsSL "https://github.com/bazelbuild/bazelisk/releases/download/v1.16.0/bazelisk-linux-${ARCH}" -o /usr/local/bin/bazel && chmod +x /usr/local/bin/bazel

    ######### run test for tidb steps ########
    # git clone https://github.com/pingap/tidb.git --branch release-7.1
    # cd tidb && make bazel_coverage_test
    ```
- or base on `rocky:9`
    ```Dockerfile
    # Base image
    FROM rockylinux:9.2

    # golang tool
    ENV GOLANG_VERSION 1.20.10
    ENV ARCH amd64
    ENV GOLANG_DOWNLOAD_URL https://dl.google.com/go/go$GOLANG_VERSION.linux-$ARCH.tar.gz
    ENV GOPATH /go
    ENV GOROOT /usr/local/go
    ENV PATH $GOPATH/bin:$GOROOT/bin:$PATH
    RUN curl -fsSL "$GOLANG_DOWNLOAD_URL" -o golang.tar.gz && tar -C /usr/local -xzf golang.tar.gz && rm golang.tar.gz

    RUN yum update -y && \
        yum groupinstall 'Development Tools' -y && \
        yum install unzip -y && \
        yum clean all

    # bazel tool
    RUN curl -fsSL "https://github.com/bazelbuild/bazelisk/releases/download/v1.16.0/bazelisk-linux-${ARCH}" -o /usr/local/bin/bazel && chmod +x /usr/local/bin/bazel

    ######### run test for tidb steps ########
    # git clone https://github.com/pingap/tidb.git --branch release-7.1
    # cd tidb && make bazel_coverage_test
    ```

## Run after merged

None
