def tiup_desc = ""
def desc = ""

def tiflash_sha1, tarball_name, dir_name

def download = { name, version, os, arch ->
    if (os == "linux") {
        platform = "centos7"
    } else if (os == "darwin") {
        platform = "darwin"
    } else {
        sh """
        exit 1
        """
    }

    if (arch == "arm64") {
        tarball_name = "${name}-${os}-${arch}.tar.gz"
    } else {
        tarball_name = "${name}.tar.gz"
    }
    if (HOTFIX_TAG != "nightly" && HOTFIX_TAG > "v4.0.0") {
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${importer_sha1}/${platform}/${tarball_name}
    """
    }else{
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/${importer_sha1}/${platform}/${tarball_name}
    """
    }
}

def unpack = { name, version, os, arch ->
    if (arch == "arm64") {
        tarball_name = "${name}-${os}-${arch}.tar.gz"
    } else {
        tarball_name = "${name}.tar.gz"
    }

    sh """
    tar -zxf ${tarball_name}
    rm -rf ${name}*.tar.gz
    """
}

def pack = { name, version, os, arch ->

    sh """
    [ -d package ] || mkdir package
    """

    if (os == "linux" && arch == "amd64") {
        sh """
        tar -C bin/ -czvf package/tikv-${name}-${version}-${os}-${arch}.tar.gz tikv-importer
        rm -rf bin
        """
    } else {
        sh """
        tar -C ${name}-*/bin/ -czvf package/tikv-${name}-${version}-${os}-${arch}.tar.gz tikv-importer
        rm -rf ${name}-*
        """
    }

    sh """
    tiup mirror publish tikv-${name} ${TIDB_VERSION} package/tikv-${name}-${version}-${os}-${arch}.tar.gz tikv-${name} --hide --arch ${arch} --os ${os} --desc="${desc}"
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

            if (HOTFIX_TAG != "nightly" && HOTFIX_TAG < "v4.0.0") {
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

                    importer_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=importer -version=${ORIGIN_TAG} -s=${FILE_SERVER_URL}").trim()
                }
                if (ARCH_X86) {
                    stage("tiup release tikv-importer linux amd64") {
                        update "importer", HOTFIX_TAG, "linux", "amd64"
                    }
                }
                if (ARCH_ARM) {
                    stage("tiup release tikv-importer linux arm64") {
                        update "importer", HOTFIX_TAG, "linux", "arm64"
                    }
                }
                if (ARCH_MAC) {
                    stage("tiup release tikv-importer darwin amd64") {
                        update "importer", HOTFIX_TAG, "darwin", "amd64"
                    }
                }
            }
        }
    }
} catch (Exception e) {
    echo "${e}"
    throw e
}