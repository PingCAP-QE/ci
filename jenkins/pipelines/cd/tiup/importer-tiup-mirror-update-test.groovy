def tiup_desc = ""
def desc = ""

def tiflash_sha1, tarball_name, dir_name

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

    tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${tag}/${importer_sha1}/${platform}/${tarball_name}
    """
}

def unpack = { name, version, os, arch ->
    tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    tar -zxf ${tarball_name}
    rm -rf ${name}*.tar.gz
    """
}

def pack = { name, version, os, arch ->

    sh """
    [ -d package ] || mkdir package
    """

    sh """
    tar -C bin/ -czvf package/tikv-${name}-${version}-${os}-${arch}.tar.gz tikv-importer
    rm -rf bin
    """

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

            if (RELEASE_TAG != "nightly" && RELEASE_TAG < "v4.0.0") {
                stage("Get hash") {
                    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                    if (RELEASE_TAG == "nightly") {
                        tag = "master"
                    } else {
                        tag = RELEASE_TAG
                    }

                    if (TIDB_VERSION == "") {
                        TIDB_VERSION = RELEASE_TAG
                    }

                    importer_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=importer -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                }

                stage("tiup release tikv-importer linux amd64") {
                    update "importer", RELEASE_TAG, "linux", "amd64"
                }

                stage("tiup release tikv-importer linux arm64") {
                    update "importer", RELEASE_TAG, "linux", "arm64"
                }

                stage("tiup release tikv-importer darwin amd64") {
                    update "importer", RELEASE_TAG, "darwin", "amd64"
                }

                if (RELEASE_TAG >="v5.1.0" || RELEASE_TAG =="nightly") {
                    stage("tiup release tikv-importer darwin arm64") {
                        update "importer", RELEASE_TAG, "darwin", "arm64"
                    }
                }
            }
        }
    }
} catch (Exception e) {
    echo "${e}"
    throw e
}