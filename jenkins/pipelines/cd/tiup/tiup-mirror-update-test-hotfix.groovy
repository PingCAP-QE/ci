/*
* @TIDB_TAG
* @TIKV_TAG
* @PD_TAG
* @BINLOG_TAG
* @CDC_TAG
* @BR_TAG
* @DUMPLING_TAG
* @IMPORTER_TAG
* @TIFLASH_TAG
* @ARCH_ARM
* @ARCH_X86
* @ARCH_MAC
* @ARCH_MAC_ARM
*/

def get_hash = { hash_or_branch, repo ->
    if (hash_or_branch.length() == 40) {
        return hash_or_branch
    }
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${hash_or_branch} -s=${FILE_SERVER_URL}").trim()
}

def tidb_sha1, tikv_sha1, pd_sha1, tidb_ctl_sha1, tidb_binlog_sha1, platform, tag, tarball_name, tidb_version
def tidb_desc = "TiDB is an open source distributed HTAP database compatible with the MySQL protocol"
def tikv_desc = "Distributed transactional key-value database, originally created to complement TiDB"
def pd_desc = "PD is the abbreviation for Placement Driver. It is used to manage and schedule the TiKV cluster"
def ctl_desc = "TiDB controller suite"
def binlog_desc = ""
def pump_desc = "The pump componet of TiDB binlog service"
def drainer_desc = "The drainer componet of TiDB binlog service"
def pd_recover_desc = "PD Recover is a disaster recovery tool of PD, used to recover the PD cluster which cannot start or provide services normally"


def download = { name, hash, os, arch ->
    if (os == "linux") {
        platform = "centos7"
    } else if (os == "darwin" && arch == "amd64") {
        platform = "darwin"
    } else if (os == "darwin" && arch == "arm64") {
        platform = "darwin-arm64"
    }  else {
        sh """
        exit 1
        """
    }

    if (name == "tidb" || name == "tikv" || name == "pd") {
        if (arch == "arm64" && os != "darwin") {
            tarball_name = "${name}-server-${os}-${arch}.tar.gz"
        } else {
            tarball_name = "${name}-server.tar.gz"
        }
    } else {
        if (arch == "arm64" && os != "darwin") {
            tarball_name = "${name}-${os}-${arch}.tar.gz"
        } else {
            tarball_name = "${name}.tar.gz"
        }
    }
    if (HOTFIX_TAG != "nightly" && HOTFIX_TAG >= "v4.0.0") {
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${hash}/${platform}/${tarball_name}
    """
    } else {
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/${hash}/${platform}/${tarball_name}
    """
    }
}

def unpack = { name, os, arch ->
    if (name == "tidb" || name == "tikv" || name == "pd") {
        if (arch == "arm64" && os != "darwin") {
            tarball_name = "${name}-server-${os}-${arch}.tar.gz"
        } else {
            tarball_name = "${name}-server.tar.gz"
        }
    } else {
        if (arch == "arm64" && os != "darwin") {
            tarball_name = "${name}-${os}-${arch}.tar.gz"
        } else {
            tarball_name = "${name}.tar.gz"
        }
    }
    sh """
    tar -zxf ${tarball_name}
    """
}

def pack = { name, version, os, arch ->
    sh """
    mkdir -p ctls
    """
    if (os == "linux" && arch == "amd64") {
        if (name == "tidb") {
            sh """
            tiup package ${name}-server -C bin --name=${name} --release=${version} --entry=${name}-server --os=${os} --arch=${arch} --desc="${tidb_desc}"
            tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server --arch ${arch} --os ${os} --desc="${tidb_desc}"
            """
        } else if (name == "tikv") {
            sh """
            mv bin/${name}-ctl ctls/
            tiup package ${name}-server -C bin --name=${name} --release=${version} --entry=${name}-server --os=${os} --arch=${arch} --desc="${tikv_desc}"
            tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server --arch ${arch} --os ${os} --desc="${tikv_desc}"
            """
        } else if (name == "pd") {
            sh """
            mv bin/${name}-ctl ctls/
            tiup package ${name}-server -C bin --name=${name} --release=${version} --entry=${name}-server --os=${os} --arch=${arch} --desc="${pd_desc}"
            tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server --arch ${arch} --os ${os} --desc="${pd_desc}"

            tiup package ${name}-recover -C bin --name=${name}-recover --release=${version} --entry=${name}-recover --os=${os} --arch=${arch} --desc="${pd_recover_desc}"
            tiup mirror publish ${name}-recover ${tidb_version} package/${name}-recover-${version}-${os}-${arch}.tar.gz ${name}-recover --arch ${arch} --os ${os} --desc="${pd_recover_desc}"
            """
        } else if (name == 'tidb-binlog') {
            sh """
            tiup package pump -C bin --hide --name=pump --release=${version} --entry=pump --os=${os} --arch=${arch} --desc="${pump_desc}"
            tiup mirror publish pump ${tidb_version} package/pump-${version}-${os}-${arch}.tar.gz pump --arch ${arch} --os ${os} --desc="${pump_desc}"
            tiup package drainer -C bin --hide --name=drainer --release=${version} --entry=drainer --os=${os} --arch=${arch} --desc="${drainer_desc}"
            tiup mirror publish drainer ${tidb_version} package/drainer-${version}-${os}-${arch}.tar.gz drainer --arch ${arch} --os ${os} --desc="${drainer_desc}"

            mv bin/binlogctl ctls/
            """
        } else if (name == "tidb-ctl") {
            sh """
            mv bin/${name} ctls/
            """
        } else {
            sh """
            tiup package ${name} -C bin --hide --name=${name} --release=${version} --entry=${name} --os=${os} --arch=${arch} --desc=""
            tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name} --arch ${arch} --os ${os} --desc=""
            """
        }
    } else {
        if (name == "tidb") {
            sh """
            tiup package ${name}-server -C ${name}-${version}-${os}-${arch}/bin --name=${name} --release=${version} --entry=${name}-server --os=${os} --arch=${arch} --desc="${tidb_desc}"
            tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server --arch ${arch} --os ${os} --desc="${tidb_desc}"
            """
        } else if (name == "tikv") {
            sh """
            mv ${name}-${version}-${os}-${arch}/bin/${name}-ctl ctls/
            tiup package ${name}-server -C ${name}-${version}-${os}-${arch}/bin --hide --name=${name} --release=${version} --entry=${name}-server --os=${os} --arch=${arch} --desc="${tikv_desc}"
            tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server --arch ${arch} --os ${os} --desc="${tikv_desc}"
            """
        } else if (name == "pd") {
            sh """
            mv ${name}-${version}-${os}-${arch}/bin/${name}-ctl ctls/
            tiup package ${name}-server -C ${name}-${version}-${os}-${arch}/bin --hide --name=${name} --release=${version} --entry=${name}-server --os=${os} --arch=${arch} --desc="${pd_desc}"
            tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server --arch ${arch} --os ${os} --desc="${pd_desc}"

            tiup package ${name}-recover -C ${name}-${version}-${os}-${arch}/bin --hide --name=${name}-recover --release=${version} --entry=${name}-recover --os=${os} --arch=${arch} --desc="${pd_recover_desc}"
            tiup mirror publish ${name}-recover ${tidb_version} package/${name}-recover-${version}-${os}-${arch}.tar.gz ${name}-recover --arch ${arch} --os ${os} --desc="${pd_recover_desc}"
            """
        } else if (name == 'tidb-binlog') {
            sh """
            mv ${name}-${version}-${os}-${arch}/bin/binlogctl ctls/
            tiup package pump -C ${name}-${version}-${os}-${arch}/bin --hide --name=pump --release=${version} --entry=pump --os=${os} --arch=${arch} --desc="${pump_desc}"
            tiup mirror publish pump ${tidb_version} package/pump-${version}-${os}-${arch}.tar.gz pump --arch ${arch} --os ${os} --desc="${pump_desc}"
            tiup package drainer -C ${name}-${version}-${os}-${arch}/bin --hide --name=drainer --release=${version} --entry=drainer --os=${os} --arch=${arch} --desc="${drainer_desc}"
            tiup mirror publish drainer ${tidb_version} package/drainer-${version}-${os}-${arch}.tar.gz drainer --arch ${arch} --os ${os} --desc="${drainer_desc}"
            """
        } else if (name == "tidb-ctl") {
            sh """
            # mv ${name}-${version}-${os}-${arch}/bin/${name} ctls/
            mv ${name}-*/bin/${name} ctls/
            """
        } else {
            sh """
            tiup package ${name} -C ${name}-${version}-${os}-${arch}/bin --hide --name=${name} --release=${version} --entry=${name} --os=${os} --arch=${arch} --desc=""
            tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name} --arch ${arch} --os ${os} --desc=""
            """
        }
    }

    sh """
    rm -rf ${name}*.tar.gz
    """
}

def update = { name, version, hash, os, arch ->
    try {
        download name, hash, os, arch
        unpack name, os, arch
        pack name, version, os, arch
    } catch (e) {
        echo "update ${name}-${version}-${os}-${arch}: ${e}"
        throw e
    }
}

def update_ctl = { version, os, arch ->
    sh """
    mkdir -p tiup
    """
    dir("tiup") {
        dir("components/ctl") {
            // tiup-ctl 一般不会变更，可以固定使用 v1.4.0 版本
            sh "curl -L https://github.com/pingcap/tiup/releases/download/v1.4.0/tiup-v1.4.0-${os}-${arch}.tar.gz | tar -xz bin/tiup-ctl"
        }
    }

    if (os == "linux") {
        platform = "centos7"
    } else if (os == "darwin" && arch == "amd64") {
        platform = "darwin"
    } else if (os == "darwin" && arch == "arm64") {
        platform = "darwin-arm64"
    } else {
        sh """
        exit 1
        """
    }

    if (HOTFIX_TAG == "nightly" || HOTFIX_TAG >= "v4.0.0") {
        if (HOTFIX_TAG != "nightly") {
            sh """
            wget ${FILE_SERVER_URL}/download/builds/pingcap/ticdc/optimization/${ticdc_sha1}/${platform}/ticdc-${os}-${arch}.tar.gz
            """
        } else {
            sh """
            wget ${FILE_SERVER_URL}/download/builds/pingcap/ticdc/${ticdc_sha1}/${platform}/ticdc-${os}-${arch}.tar.gz
            """
        }
        sh """
        tar xf ticdc-${os}-${arch}.tar.gz
        cp ticdc-${os}-${arch}/bin/cdc ctls/
        """
    }

    sh """
    mv tiup/components/ctl/bin/tiup-ctl ctls/ctl
    curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-${os}-${arch}.tar.gz | tar xz
    mv etcd-v3.3.10-${os}-${arch}/etcdctl ctls/
    tiup package \$(ls ctls) -C ctls --name=ctl --release=${version} --entry=ctl --os=${os} --arch=${arch} --desc="${ctl_desc}"
    tiup mirror publish ctl ${tidb_version} package/ctl-${version}-${os}-${arch}.tar.gz ctl --arch ${arch} --os ${os} --desc="${ctl_desc}"
    rm -rf ctls
    """
}

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

                if (HOTFIX_TAG == "nightly") {
                    tag = "master"
                } else {
                    tag = HOTFIX_TAG
                }

                tidb_sha1 = get_hash(TIDB_TAG, "tidb")
                tidb_ctl_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb-ctl/master/sha1").trim()
                tikv_sha1 = get_hash(TIKV_TAG, "tikv")
                pd_sha1 = get_hash(PD_TAG, "pd")
                tidb_binlog_sha1 = get_hash(BINLOG_TAG, "tidb-binlog")
                if (HOTFIX_TAG == "nightly" || HOTFIX_TAG >= "v4.0.0") {
                    ticdc_sha1 = get_hash(CDC_TAG, "ticdc")
                }
            }

            if (HOTFIX_TAG == "nightly") {
                stage("Get version info when nightly") {
                    dir("tidb") {
                        // sh"""
                        // wget ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz
                        // tar xf tidb-server.tar.gz
                        // """
                        // tidb_version = sh(returnStdout: true, script: "./bin/tidb-server -V | awk 'NR==1{print \$NF}' | sed -r 's/(^[^-]*).*/\\1/'").trim()
                        tidb_version = "v5.0.0"
                        time = sh(returnStdout: true, script: "date '+%Y%m%d'").trim()
                        tidb_version = "${tidb_version}-nightly-${time}"
                    }
                }
            } else {
                tidb_version = HOTFIX_TAG
            }
            if (params.ARCH_X86) {
                stage("TiUP build tidb on linux/amd64") {
                    update "tidb", HOTFIX_TAG, tidb_sha1, "linux", "amd64"
                    update "tidb-ctl", HOTFIX_TAG, tidb_ctl_sha1, "linux", "amd64"
                    update "tikv", HOTFIX_TAG, tikv_sha1, "linux", "amd64"
                    update "pd", HOTFIX_TAG, pd_sha1, "linux", "amd64"
                    update "tidb-binlog", HOTFIX_TAG, tidb_binlog_sha1, "linux", "amd64"
                    update_ctl HOTFIX_TAG, "linux", "amd64"
                }
            }
            if (params.ARCH_ARM) {
                stage("TiUP build tidb on linux/arm64") {
                    update "tidb", HOTFIX_TAG, tidb_sha1, "linux", "arm64"
                    update "tidb-ctl", HOTFIX_TAG, tidb_ctl_sha1, "linux", "arm64"
                    update "tikv", HOTFIX_TAG, tikv_sha1, "linux", "arm64"
                    update "pd", HOTFIX_TAG, pd_sha1, "linux", "arm64"
                    update "tidb-binlog", HOTFIX_TAG, tidb_binlog_sha1, "linux", "arm64"
                    update_ctl HOTFIX_TAG, "linux", "arm64"
                }
            }
            if (params.ARCH_MAC) {
                stage("TiUP build tidb on darwin/amd64") {
                    update "tidb", HOTFIX_TAG, tidb_sha1, "darwin", "amd64"
                    update "tidb-ctl", HOTFIX_TAG, tidb_ctl_sha1, "darwin", "amd64"
                    update "tikv", HOTFIX_TAG, tikv_sha1, "darwin", "amd64"
                    update "pd", HOTFIX_TAG, pd_sha1, "darwin", "amd64"
                    update "tidb-binlog", HOTFIX_TAG, tidb_binlog_sha1, "darwin", "amd64"
                    update_ctl HOTFIX_TAG, "darwin", "amd64"
                }
            }
            if (params.ARCH_MAC_ARM) {
                stage("TiUP build tidb on darwin/arm64") {
                    update "tidb", HOTFIX_TAG, tidb_sha1, "darwin", "arm64"
                    update "tidb-ctl", HOTFIX_TAG, tidb_ctl_sha1, "darwin", "arm64"
                    update "tikv", HOTFIX_TAG, tikv_sha1, "darwin", "arm64"
                    update "pd", HOTFIX_TAG, pd_sha1, "darwin", "arm64"
                    update "tidb-binlog", HOTFIX_TAG, tidb_binlog_sha1, "darwin", "arm64"
                    update_ctl HOTFIX_TAG, "darwin", "arm64"
                }
            }

            // stage("Upload") {
            //     upload "package"
            // }

            def paramsCDC = [
                    string(name: "HOTFIX_TAG", value: "${HOTFIX_TAG}"),
                    string(name: "TIDB_VERSION", value: "${tidb_version}"),
                    string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                    string(name: "ORIGIN_TAG", value: "${CDC_TAG}"),
                    [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                    [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
                    
            ]

            stage("TiUP build cdc") {
                build(job: "cdc-tiup-mirror-update-test-hotfix", wait: true, parameters: paramsCDC)
            }

            def paramsBR = [
                    string(name: "HOTFIX_TAG", value: "${HOTFIX_TAG}"),
                    string(name: "TIDB_VERSION", value: "${tidb_version}"),
                    string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                    string(name: "ORIGIN_TAG", value: "${BR_TAG}"),
                    [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                    [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
            ]

            stage("TiUP build br") {
                build(job: "br-tiup-mirror-update-test-hotfix", wait: true, parameters: paramsBR)
            }

            def paramsDUMPLING = [
                    string(name: "HOTFIX_TAG", value: "${HOTFIX_TAG}"),
                    string(name: "TIDB_VERSION", value: "${tidb_version}"),
                    string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                    string(name: "ORIGIN_TAG", value: "${DUMPLING_TAG}"),
                    [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                    [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
            ]

            stage("TiUP build dumpling") {
                build(job: "dumpling-tiup-mirror-update-test-hotfix", wait: true, parameters: paramsDUMPLING)
            }

            // since 4.0.12 the same as br
            def paramsLIGHTNING = [
                    string(name: "HOTFIX_TAG", value: "${HOTFIX_TAG}"),
                    string(name: "TIDB_VERSION", value: "${tidb_version}"),
                    string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                    string(name: "ORIGIN_TAG", value: "${BR_TAG}"),
                    [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                    [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
            ]

            stage("TiUP build lightning") {
                build(job: "lightning-tiup-mirror-update-test-hotfix", wait: true, parameters: paramsLIGHTNING)
            }

            def paramsIMPORTER = [
                    string(name: "HOTFIX_TAG", value: "${HOTFIX_TAG}"),
                    string(name: "TIDB_VERSION", value: "${tidb_version}"),
                    string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                    string(name: "ORIGIN_TAG", value: "${IMPORTER_TAG}"),
                    [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                    [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
            ]

            if (HOTFIX_TAG != "nightly" && HOTFIX_TAG < "v4.0.0") {
                stage("TiUP build importer") {
                    build(job: "importer-tiup-mirror-update-test-hotfix", wait: true, parameters: paramsIMPORTER)
                }
            }

            def paramsTIFLASH = [
                    string(name: "HOTFIX_TAG", value: "${HOTFIX_TAG}"),
                    string(name: "TIDB_VERSION", value: "${tidb_version}"),
                    string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                    string(name: "ORIGIN_TAG", value: "${TIFLASH_TAG}"),
                    [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                    [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
            ]

            stage("TiUP build tiflash") {
                build(job: "tiflash-tiup-mirror-update-test-hotfix", wait: true, parameters: paramsTIFLASH)
            }

            def paramsGRANFANA = [
                    string(name: "HOTFIX_TAG", value: "${HOTFIX_TAG}"),
                    string(name: "TIDB_VERSION", value: "${tidb_version}"),
                    string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                    string(name: "ORIGIN_TAG", value: "${TIFLASH_TAG}"),
                    [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                    [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
            ]

            stage("TiUP build grafana") {
                build(job: "grafana-tiup-mirror-update-test-hotfix", wait: true, parameters: paramsGRANFANA)
            }

            def paramsPROMETHEUS = [
                    string(name: "HOTFIX_TAG", value: "${HOTFIX_TAG}"),
                    string(name: "TIDB_VERSION", value: "${tidb_version}"),
                    string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                    string(name: "ORIGIN_TAG", value: "${TIFLASH_TAG}"),
                    [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                    [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
            ]

            stage("TiUP build prometheus") {
                build(job: "prometheus-tiup-mirrior-update-test-hotfix", wait: true, parameters: paramsPROMETHEUS)
            }

            // def params2 = [
            //         string(name: "HOTFIX_TAG", value: "${HOTFIX_TAG}"),
            //         string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
            // ]


            // stage("TiUP build node_exporter") {
            //     build(job: "node_exporter-tiup-mirrior-update-test", wait: true, parameters: params2)
            // }

            // stage("TiUP build blackbox_exporter") {
            //     build(job: "blackbox_exporter-tiup-mirrior-update-test", wait: true, parameters: params2)
            // }

            // stage("TiUP build alertmanager") {
            //     build(job: "alertmanager-tiup-mirrior-update-test", wait: true, parameters: params2)
            // }

            // stage("TiUP build pushgateway") {
            //     build(job: "pushgateway-tiup-mirrior-update-test", wait: true, parameters: params2)
            // }
        }
    }
}
