def tiup_desc = ""
def desc = "TiDB Lightning is a tool used for fast full import of large amounts of data into a TiDB cluster"

def tiflash_sha1, tarball_name, dir_name

def get_hash = { hash_or_branch, repo ->
    if (hash_or_branch.length() == 40) {
        return hash_or_branch
    }
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${hash_or_branch} -s=${FILE_SERVER_URL}").trim()
}

def download = { name, version, os, arch ->
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

    if (arch == "arm64" && os != "darwin") {
        tarball_name = "${name}-${os}-${arch}.tar.gz"
    } else {
        tarball_name = "${name}.tar.gz"
    }

    if (HOTFIX_TAG != "nightly" && HOTFIX_TAG > "v4.0.0") {
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${tag}/${lightning_sha1}/${platform}/${tarball_name}
    """
    } else {
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/${tag}/${lightning_sha1}/${platform}/${tarball_name}
    """
    }

}

def unpack = { name, version, os, arch ->
    if (arch == "arm64" && os != "darwin") {
        tarball_name = "${name}-${os}-${arch}.tar.gz"
    } else {
        tarball_name = "${name}.tar.gz"
    }

    sh """
    tar -zxf ${tarball_name}
    """
}

def pack = { name, version, os, arch ->

    sh """
    rm -rf ${name}*.tar.gz
    [ -d package ] || mkdir package
    """

    if (os == "linux" && arch == "amd64") {
        sh """
        tar -C bin/ -czvf package/tidb-lightning-${version}-${os}-${arch}.tar.gz tidb-lightning
        rm -rf bin
        """
    } else {
        sh """
        tar -C ${name}-*/bin/ -czvf package/tidb-lightning-${version}-${os}-${arch}.tar.gz tidb-lightning
        rm -rf ${name}-*
        """
    }

    sh """
    tiup mirror publish tidb-lightning ${TIDB_VERSION} package/tidb-lightning-${version}-${os}-${arch}.tar.gz tidb-lightning --standalone --arch ${arch} --os ${os} --desc="${desc}"
    """
}

def update = { name, version, os, arch ->
    download name, version, os, arch
    unpack name, version, os, arch
    pack name, version, os, arch
}

try {
    node("build_go1130") {
        container("golang") {
            stage("Prepare") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                deleteDir()
            }
       
            checkout scm
            def util = load "jenkins/pipelines/cd/tiup/tiup_utils.groovy"

            stage("Install tiup") {
                util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
            }

            stage("Get hash") {
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                if (HOTFIX_TAG == "nightly") {
                    tag = "master"
                } else {
                    tag = HOTFIX_TAG
                }

                if (TIDB_VERSION == "") {
                    TIDB_VERSION = HOTFIX_TAG
                }
                // After v4.0.11, we use br repo instead of br repo, and we should not maintain old version, if we indeed need, we can use the old version of this groovy file
                lightning_sha1 = ""
                if (HOTFIX_TAG == "nightly" || HOTFIX_TAG >= "v5.2.0") {
                    lightning_sha1 = get_hash(ORIGIN_TAG,"tidb")
                } else {
                    lightning_sha1 = get_hash(ORIGIN_TAG,"br")
                }
            }
            if (params.ARCH_X86) {
                stage("tiup release tidb-lightning linux amd64") {
                    update "br", HOTFIX_TAG, "linux", "amd64"
                }
            }
            if (params.ARCH_ARM) {
                stage("tiup release tidb-lightning linux arm64") {
                    update "br", HOTFIX_TAG, "linux", "arm64"
                }
            }
            if (params.ARCH_MAC) {
                stage("tiup release tidb-lightning darwin amd64") {
                    update "br", HOTFIX_TAG, "darwin", "amd64"
                }
            }
            if (params.ARCH_MAC_ARM) {
                stage("tiup release tidb-lightning darwin arm64") {
                    update "br", HOTFIX_TAG, "darwin", "arm64"
                }
            }
        }
    }
} catch (Exception e) {
    echo "${e}"
    throw e
}