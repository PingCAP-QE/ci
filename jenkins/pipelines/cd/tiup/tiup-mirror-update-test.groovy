def tidb_sha1, tikv_sha1, pd_sha1, tidb_ctl_sha1, tidb_binlog_sha1, platform, tag, tarball_name, tidb_version
def tidb_desc = "TiDB is an open source distributed HTAP database compatible with the MySQL protocol"
def tikv_desc = "Distributed transactional key-value database, originally created to complement TiDB"
def pd_desc = "PD is the abbreviation for Placement Driver. It is used to manage and schedule the TiKV cluster"
def ctl_desc = "TiDB controller suite"
def binlog_desc = ""
def pump_desc = "The pump componet of TiDB binlog service"
def drainer_desc = "The drainer componet of TiDB binlog service"
def pd_recover_desc = "PD Recover is a disaster recovery tool of PD, used to recover the PD cluster which cannot start or provide services normally"

def install_tiup = { bin_dir ->
    sh """
    wget -q https://tiup-mirrors.pingcap.com/tiup-linux-amd64.tar.gz
    sudo tar -zxf tiup-linux-amd64.tar.gz -C ${bin_dir}
    sudo chmod 755 ${bin_dir}/tiup
    rm -rf ~/.tiup
    mkdir -p /home/jenkins/.tiup/bin/
    curl https://tiup-mirrors.pingcap.com/root.json -o /home/jenkins/.tiup/bin/root.json
    mkdir -p ~/.tiup/keys
    set +x
    echo ${PINGCAP_PRIV_KEY} | base64 -d > ~/.tiup/keys/private.json
    set -x
    """
}

def install_qshell = { bin_dir ->
    sh """
    wget -q https://tiup-mirrors.pingcap.com/qshell-linux-amd64.tar.gz
    sudo tar -zxf qshell-linux-amd64.tar.gz -C ${bin_dir}
    sudo chmod 755 ${bin_dir}/qshell
    set +x
    qshell account ${QSHELL_KEY} ${QSHELL_SEC} tiup-mirror-update --overwrite
    set -x
    """
}

def download = { name, hash, os, arch ->
    if (os == "linux") {
        platform = "centos7"
    } else if (os == "darwin") {
        platform = "darwin"
    } else {
        sh """
        exit 1
        """
    }

    if (name == "tidb" || name == "tikv" || name == "pd") {
        if (arch == "arm64") {
            tarball_name = "${name}-server-${os}-${arch}.tar.gz"
        } else {
            tarball_name = "${name}-server.tar.gz"
        }
    } else {
        if (arch == "arm64") {
            tarball_name = "${name}-${os}-${arch}.tar.gz"
        } else {
            tarball_name = "${name}.tar.gz"
        }
    }
    if (RELEASE_TAG != "nightly" && RELEASE_TAG > "v4.0.8" && arch == "amd64" && os == "linux") {
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
        if (arch == "arm64") {
            tarball_name = "${name}-server-${os}-${arch}.tar.gz"
        } else {
            tarball_name = "${name}-server.tar.gz"
        }
    } else {
        if (arch == "arm64") {
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

def upload = { dir ->
    sh """
    rm -rf ~/.qshell/qupload
    qshell qupload2 --src-dir=${dir} --bucket=tiup-mirrors --overwrite
    """
}

def update = { name, version, hash, os, arch ->
    try {
        download name, hash, os, arch
        unpack name, os, arch
        pack name, version, os, arch
    } catch (e) {
        echo "update ${name}-${version}-${os}-${arch}: ${e}"
    }
}

def update_ctl = { version, os, arch ->
    sh """
    mkdir -p tiup
    """
    dir("tiup") {
        git credentialsId: 'github-sre-bot-ssh', url: "git@github.com:pingcap-incubator/tiup.git", branch: "master"
        sh """
         cd components/ctl/
         GOOS=$os GOARCH=$arch go build -o ctl
         """
    }

    if (os == "linux") {
        platform = "centos7"
    } else if (os == "darwin") {
        platform = "darwin"
    } else {
        sh """
        exit 1
        """
    }

    if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
        if (RELEASE_TAG != "nightly" && arch == "amd64" && os == "linux") {
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
    mv tiup/components/ctl/ctl ctls/
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

            stage("Install tiup/qshell") {
                install_tiup "/usr/local/bin"
                install_qshell "/usr/local/bin"
            }

            stage("Get component hash") {
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                if (RELEASE_TAG == "nightly") {
                    tag = "master"
                } else {
                    tag = RELEASE_TAG
                }

                tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                tidb_ctl_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb-ctl/master/sha1").trim()
                tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                tidb_binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
                    ticdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ticdc -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                }
            }

            if (RELEASE_TAG == "nightly") {
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
                tidb_version = RELEASE_TAG
            }

            stage("TiUP build tidb on linux/amd64") {
                update "tidb", RELEASE_TAG, tidb_sha1, "linux", "amd64"
                update "tidb-ctl", RELEASE_TAG, tidb_ctl_sha1, "linux", "amd64"
                update "tikv", RELEASE_TAG, tikv_sha1, "linux", "amd64"
                update "pd", RELEASE_TAG, pd_sha1, "linux", "amd64"
                update "tidb-binlog", RELEASE_TAG, tidb_binlog_sha1, "linux", "amd64"
                update_ctl RELEASE_TAG, "linux", "amd64"
            }

            stage("TiUP build tidb on linux/arm64") {
                update "tidb", RELEASE_TAG, tidb_sha1, "linux", "arm64"
                update "tidb-ctl", RELEASE_TAG, tidb_ctl_sha1, "linux", "arm64"
                update "tikv", RELEASE_TAG, tikv_sha1, "linux", "arm64"
                update "pd", RELEASE_TAG, pd_sha1, "linux", "arm64"
                update "tidb-binlog", RELEASE_TAG, tidb_binlog_sha1, "linux", "arm64"
                update_ctl RELEASE_TAG, "linux", "arm64"
            }

            stage("TiUP build tidb on darwin/amd64") {
                update "tidb", RELEASE_TAG, tidb_sha1, "darwin", "amd64"
                update "tidb-ctl", RELEASE_TAG, tidb_ctl_sha1, "darwin", "amd64"
                update "tikv", RELEASE_TAG, tikv_sha1, "darwin", "amd64"
                update "pd", RELEASE_TAG, pd_sha1, "darwin", "amd64"
                update "tidb-binlog", RELEASE_TAG, tidb_binlog_sha1, "darwin", "amd64"
                update_ctl RELEASE_TAG, "darwin", "amd64"
            }

            // stage("Upload") {
            //     upload "package"
            // }

            def params1 = [
                    string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
                    string(name: "TIDB_VERSION", value: "${tidb_version}"),
                    string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
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

            if (RELEASE_TAG != "nightly" && RELEASE_TAG < "v4.0.3") {
                stage("TiUP build importer") {
                    build(job: "importer-tiup-mirror-update-test", wait: true, parameters: params1)
                }
            }

            stage("TiUP build tiflash") {
                build(job: "tiflash-tiup-mirror-update-test", wait: true, parameters: params1)
            }

            stage("TiUP build grafana") {
                build(job: "grafana-tiup-mirror-update-test", wait: true, parameters: params1)
            }

            stage("TiUP build prometheus") {
                build(job: "prometheus-tiup-mirrior-update-test", wait: true, parameters: params1)
            }

            if (RELEASE_TAG == "nightly") {
                stage("TiUP build dm") {
                    build(job: "dm-tiup-mirror-update-test", wait: true, parameters: params1)
                }
            }

            def params2 = [
                    string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
                    string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
            ]


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