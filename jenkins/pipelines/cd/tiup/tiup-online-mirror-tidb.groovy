package cd.tiup

def platform, tag, tarball_name
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
    } else {
        sh """
        exit 1
        """
    }

    tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${tag}/${hash}/${platform}/${tarball_name}
    """
}

def unpack = { name, os, arch ->
    tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    tar -zxf ${tarball_name}
    """
}

def pack = { name, version, os, arch ->
    sh "mkdir -p ctls"

    if (name == "tidb") {
        sh "tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server"
    } else if (name == "tikv") {
        sh """
        mv bin/${name}-ctl ctls/
        tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server
        """
    } else if (name == "pd") {
        sh """
        mv bin/${name}-ctl ctls/
        tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server
        tar -C bin -czvf package/${name}-recover-${version}-${os}-${arch}.tar.gz ${name}-recover
        """
    } else if (name == 'tidb-binlog') {
        sh """
        tar -C bin -czvf package/pump-${version}-${os}-${arch}.tar.gz pump
        tar -C bin -czvf package/drainer-${version}-${os}-${arch}.tar.gz drainer
        """
    } else if (name == "tidb-ctl") {
        sh "mv bin/${name} ctls/"
    } else {
        sh "tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz ${name}"
    }

    sh "rm -rf ${name}*.tar.gz"
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
            // tiup-ctl 一般不会变更，可以固定使用 v1.8.1 版本
            sh "curl -L http://fileserver.pingcap.net/download/tiup/releases/v1.8.1/tiup-v1.8.1-${os}-${arch}.tar.gz | tar -xz bin/tiup-ctl"
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


    lightning_tarball_name = "br-${os}-${arch}.tar.gz"
    lightning_ctl_bin_dir = "bin/tidb-lightning-ctl"


    if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
        if (RELEASE_TAG != "nightly") {
            sh """
            wget ${FILE_SERVER_URL}/download/builds/pingcap/ticdc/optimization/${RELEASE_TAG}/${ticdc_sha1}/${platform}/ticdc-${os}-${arch}.tar.gz
            wget ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${RELEASE_TAG}/${lightning_sha1}/${platform}/${lightning_tarball_name}
            """
        } else {
            // seems there something not correct, will inspect later
            sh """
            wget ${FILE_SERVER_URL}/download/builds/pingcap/ticdc/master/${ticdc_sha1}/${platform}/ticdc-${os}-${arch}.tar.gz
            wget ${FILE_SERVER_URL}/download/builds/pingcap/br/master/${lightning_sha1}/${platform}/${lightning_tarball_name}
            """
        }
        sh """
        tar -zxf ${lightning_tarball_name}
        cp ${lightning_ctl_bin_dir} ctls/
        tar xf ticdc-${os}-${arch}.tar.gz
        cp bin/cdc ctls/
        """
    }

    sh """
    mv tiup/components/ctl/bin/tiup-ctl ctls/ctl
    curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-${os}-${arch}.tar.gz | tar xz
    mv etcd-v3.3.10-${os}-${arch}/etcdctl ctls/
    tar -C ctls -czvf package/ctl-${version}-${os}-${arch}.tar.gz \$(ls ctls)
    tiup mirror publish ctl ${TIDB_VERSION} package/ctl-${version}-${os}-${arch}.tar.gz ctl --arch ${arch} --os ${os} --desc="${ctl_desc}"
    rm -rf ctls
    """
}
node("build_go1130") {
    container("golang") {
        if (params.ARCH_X86) {
            deleteDir()
            stage("TiUP build tidb on linux/amd64") {
                update "tidb-ctl", RELEASE_TAG, TIDB_CTL_SHA, "linux", "amd64"
                update "tikv", RELEASE_TAG, TIKV_SHA, "linux", "amd64"
                update "pd", RELEASE_TAG, PD_SHA, "linux", "amd64"
                update "tidb-binlog", RELEASE_TAG, TIDB_BINLOG_SHA, "linux", "amd64"
                update_ctl RELEASE_TAG, "linux", "amd64"
                update "tidb", RELEASE_TAG, TIDB_SHA, "linux", "amd64"
            }
        }
        if (params.ARCH_ARM) {
            deleteDir()
            stage("TiUP build tidb on linux/arm64") {
                update "tidb-ctl", RELEASE_TAG, TIDB_CTL_SHA, "linux", "arm64"
                update "tikv", RELEASE_TAG, TIKV_SHA, "linux", "arm64"
                update "pd", RELEASE_TAG, PD_SHA, "linux", "arm64"
                update "tidb-binlog", RELEASE_TAG, TIDB_BINLOG_SHA, "linux", "arm64"
                update_ctl RELEASE_TAG, "linux", "arm64"
                update "tidb", RELEASE_TAG, TIDB_SHA, "linux", "arm64"
            }
        }
        if (params.ARCH_MAC) {
            deleteDir()
            stage("TiUP build tidb on darwin/amd64") {
                update "tidb-ctl", RELEASE_TAG, TIDB_CTL_SHA, "darwin", "amd64"
                update "tikv", RELEASE_TAG, TIKV_SHA, "darwin", "amd64"
                update "pd", RELEASE_TAG, PD_SHA, "darwin", "amd64"
                update "tidb-binlog", RELEASE_TAG, TIDB_BINLOG_SHA, "darwin", "amd64"
                update_ctl RELEASE_TAG, "darwin", "amd64"
                update "tidb", RELEASE_TAG, TIDB_SHA, "darwin", "amd64"
            }
        }
        if (params.ARCH_MAC_ARM) {
            deleteDir()
            if (RELEASE_TAG >= "v5.1.0" || RELEASE_TAG == "nightly") {
                stage("TiUP build tidb on darwin/arm64") {
                    update "tidb-ctl", RELEASE_TAG, TIDB_CTL_SHA, "darwin", "arm64"
                    update "tikv", RELEASE_TAG, TIKV_SHA, "darwin", "arm64"
                    update "pd", RELEASE_TAG, PD_SHA, "darwin", "arm64"
                    update "tidb-binlog", RELEASE_TAG, TIDB_BINLOG_SHA, "darwin", "arm64"
                    // update_ctl RELEASE_TAG, "darwin", "arm64"
                    update "tidb", RELEASE_TAG, TIDB_SHA, "darwin", "arm64"
                }
            }
        }
    }
}
