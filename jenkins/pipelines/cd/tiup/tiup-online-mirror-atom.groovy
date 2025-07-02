package cd.tiup

node("build_go1130") {
    container("golang") {
        timeout(360) {
            stage("Prepare") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                deleteDir()
            }

            checkout scm
            def util = load "jenkins/pipelines/cd/tiup/tiup_utils.groovy"

            stage("Install tiup") {
                util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
            }

            stage("Get component hash") {
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                if (RELEASE_TAG == "nightly") {
                    tag = "v8.5.0-alpha"
                    RELEASE_TAG = "v8.5.0-alpha"
                } else {
                    tag = RELEASE_TAG
                }

                tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                tidb_ctl_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -source=github -version=master -s=${FILE_SERVER_URL}").trim()
                tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                tidb_binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
                    ticdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tiflow -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                }
                lightning_sha1 = ""
                if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v5.2.0") {
                    lightning_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                } else {
                    lightning_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                }
            }

            if (RELEASE_TAG == "v8.5.0-alpha") {
                stage("Get version info when nightly") {
                    dir("tidb") {
                        // sh"""
                        // wget ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz
                        // tar xf tidb-server.tar.gz
                        // """
                        // tidb_version = sh(returnStdout: true, script: "./bin/tidb-server -V | awk 'NR==1{print \$NF}' | sed -r 's/(^[^-]*).*/\\1/'").trim()
                        tidb_version = "v8.5.0-alpha"
                        time = sh(returnStdout: true, script: "date '+%Y%m%d'").trim()
                        tidb_version = "${tidb_version}-nightly-${time}"
                        RELEASE_BRANCH = "master"
                    }
                }
            } else {
                tidb_version = RELEASE_TAG
            }

            // stage("Upload") {
            //     upload "package"
            // }

            def params1 = [
                    string(name: "RELEASE_BRANCH", value: "${RELEASE_BRANCH}"),
                    string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
                    string(name: "ORIGIN_TAG", value: ""),
                    string(name: "TIDB_VERSION", value: "${tidb_version}"),
                    string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                    [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: true],
                    [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: true],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: true],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: true],
            ]

            stage("TiUP build cdc") {
                build(job: "cdc-tiup-mirror-update-test", wait: true, parameters: params1)
            }

            stage("TiUP build br") {
                build(job: "br-tiup-mirror-update-test", wait: true, parameters: params1)
            }

            stage("TiUP build dumpling") {
                build(job: "dumpling-tiup-mirror-update-test", wait: true, parameters: params1)
            }

            stage("TiUP build lightning") {
                build(job: "lightning-tiup-mirror-update-test", wait: true, parameters: params1)
            }

            stage("TiUP build tiflash") {
                build(job: "tiflash-tiup-mirror-update-test", wait: true, parameters: params1)
            }

            stage("TiUP build grafana") {
                build(job: "grafana-tiup-mirror-update", wait: true, parameters: params1)
            }

            stage("TiUP build prometheus") {
                build(job: "prometheus-tiup-mirror-update-test", wait: true, parameters: params1)
            }

            if (RELEASE_TAG == "v8.5.0-alpha") {
                stage("TiUP build dm") {
                    build(job: "dm-tiup-mirror-update-test", wait: true, parameters: params1)
                }
            }

            stage("TiUP build prometheus") {
                build(job: "prometheus-tiup-mirror-update-test", wait: true, parameters: params1)
            }

            def params_tidb = [
                    string(name: "TIDB_SHA", value: "${tidb_sha1}"),
                    string(name: "TIKV_SHA", value: "${tikv_sha1}"),
                    string(name: "PD_SHA", value: "${pd_sha1}"),
                    string(name: "TIDB_CTL_SHA", value: "${tidb_ctl_sha1}"),
                    string(name: "TIDB_BINLOG_SHA", value: "${tidb_binlog_sha1}"),
                    string(name: "TIDB_SHA", value: "${tag}"),
                    string(name: "TIDB_VERSION", value: "${tidb_version}"),
                    string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
                    string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                    [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: true],
                    [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: true],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: true],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: true],
            ]

//            依赖前面的组件包，只能最后发布
            stage("TiUP build tidb") {
                build(job: "tiup-online-mirror-tidb", wait: true, parameters: params_tidb)
            }


        }
    }
}
