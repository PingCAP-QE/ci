CI Jobs
===

## Run before merged

- Host is `Linux` & `X86_64`, `arm64` is WIP.
- **Disabled cgroup v2 on the host**, there is a [reference](#how-to-check-cgroup-and-disable-cgroup2).
  - Currently some local tests are depended on cgroup.
- The runner requirement: `64` core cpu + `128GB` memory with golang `v1.19.7` installed
  - Tools and setting please refer to [container env](#dev-containers), you can setup in you bare hosts.
- Run as `root` user.

| Job name                                                            | Description                               | Trigger comment in PR | CI script                                                     | Can be run locally by contributors | Core Instructions to run locally                                                                                                                                                                                                                                                               |
| ------------------------------------------------------------------- | ----------------------------------------- | --------------------- | ------------------------------------------------------------- | ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [ghpr_build](/jobs/pingcap/tidb/latest/ghpr_build.groovy)           | lint check and build binary.              | `/test build`         | [link](/pipelines/pingcap/tidb/latest/ghpr_build.groovy)      | yes                                | run `make bazel_build`                                                                                                                                                                                                                                                                         |
| [ghpr-unit-test](/jobs/pingcap/tidb/latest/ghpr_unit_test.groovy)   | Unit/Func tests                           | `/test unit-test`     | [link](/pipelines/pingcap/tidb/latest/ghpr_unit_test.groovy)  | yes                                | run `make bazel_coverage_test`                                                                                                                                                                                                                                                                 | yes |
| [ghpr-check-dev](/jobs/pingcap/tidb/latest/ghpr_check.groovy)       | More static checks.                       | `/test check-dev`     | [link](/pipelines/pingcap/tidb/latest/ghpr_check.groovy)      | yes                                | run `make gogenerate check integrationtest`                                                                                                                                                                                                                                                        |
| [ghpr-check-dev2](/jobs/pingcap/tidb/latest/ghpr_check2.groovy)     | Basic function tests                      | `/test check-dev2`    | [link](/pipelines/pingcap/tidb/latest/ghpr_check2.groovy)     | yes                                | Add component binaries `tikv-server` and `pd-server` to the `bin/` dir after `make bazel_build`, then run the scripts in `scripts/pingcap/tidb` folder of  `pingcap-qe/ci` repo, [detail](https://github.com/PingCAP-QE/ci/blob/main/pipelines/pingcap/tidb/latest/ghpr_check2.groovy#L79-L86) |
| [ghpr-mysql-test](/jobs/pingcap/tidb/latest/ghpr_mysql_test.groovy) | Test for compatibility for mysql protocol | `/test mysql-test`    | [link](/pipelines/pingcap/tidb/latest/ghpr_mysql_test.groovy) | no                                 | 🔒test repo(PingCAP-QE/tidb-test) not public                                                                                                                                                                                                                                                       |


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
    # git clone https://github.com/pingap/tidb.git --branch master
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
    # git clone https://github.com/pingap/tidb.git --branch master
    # cd tidb && make bazel_coverage_test
    ```

## Run after merged

| Job name                       | Description                                                       | Run after merged | CI script                             | Core Instructions to run locally                                                                                                                                                                                                       | Can be run locally by contributors | Trigger comment in pull request |
| ------------------------------ | ----------------------------------------------------------------- | ---------------- | ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- | ------------------------------- |
| merged_build                   | Lint check and build binary                                       | yes              | merged_build.groovy                   | run `make bazel_build`                                                                                                                                                                                                                 | yes                                | N/A                             |
| merged_common_test             | integration test for jdbc                                         | yes              | merged_common_test.groovy             | 🔒test repo(pingcap/tidb-test) not public                                                                                                                                                                                               | no                                 | N/A                             |
| merged_e2e_test                | Basic E2E test                                                    | yes              | merged_e2e_test.groovy                | Run run-tests.sh shell under tests/graceshutdown and tests/globalkilltest                                                                                                                                                              | yes                                | N/A                             |
| merged_integration_br_test     | Integration test for backup and restore functions                 | yes              | merged_integration_br_test.groovy     | Run case with `tests/run.sh` shell, [the case list](https://github.com/PingCAP-QE/ci/blob/main/pipelines/pingcap/tidb/latest/merged_integration_br_test.groovy#L94~L112).                                                              | yes                                | N/A                             |
| merged_integration_cdc_test    | Integration test with TiCDC                                       | yes              | merged_integration_cdc_test.groovy    | Run case with make task in pingcap/tiflow repo: `make integration_test_mysql CASE="${CASES}"`, [the case list](https://github.com/PingCAP-QE/ci/blob/main/pipelines/pingcap/tidb/latest/merged_integration_cdc_test.groovy#L132~L143). | yes                                | N/A                             |
| merged_integration_common_test | Integration test with more client framework or test more features | yes              | merged_integration_common_test.groovy | 🔒test repo(pingcap/tidb-test) not public                                                                                                                                                                                               | no                                 | N/A                             |
| merged_integration_copr_test   | Integration test with the coprocessor module of TiKV              | yes              | merged_integration_copr_test.groovy   | Run `make push-down-test` in `tikv/tikv-copr-test` repo                                                                                                                                                                                | yes                                | N/A                             |
| merged_integration_ddl_test    | Integration test for DDL functions                                | yes              | merged_integration_ddl_test.groovy    | 🔒test repo(pingcap/tidb-test) not public                                                                                                                                                                                               | no                                 | N/A                             |
| merged_integration_jdbc_test   | Integration test more about jdbc                                  | yes              | merged_integration_jdbc_test.groovy   | 🔒test repo(pingcap/tidb-test) not public                                                                                                                                                                                               | no                                 | N/A                             |
| merged_integration_mysql_test  | Test for compatibility for mysql protocol                         | yes              | merged_integration_mysql_test.groovy  | 🔒test repo(pingcap/tidb-test) not public                                                                                                                                                                                               | no                                 | N/A                             |
| merged_sqllogic_test           | Integration test about sql logic                                  | yes              | merged_sqllogic_test.groovy           | 🔒test repo(pingcap/tidb-test) not public                                                                                                                                                                                               | no                                 | N/A                             |
| merged_tiflash_test            | Integration test with TiFlash module.                             | yes              | merged_tiflash_test.groovy            | Docker build with tidb [dockefile](https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/tidb.Dockerfile), then test with script in `pingcap/tiflash` repo: `tests/docker/run.sh`                                    | yes                                | N/A                             |
| merged_unit_test               | Unit tests                                                        | yes              | merged_unit_test.groovy               | run `./build/jenkins_unit_test`.sh                                                                                                                                                                                                     | yes                                | N/A                             |
