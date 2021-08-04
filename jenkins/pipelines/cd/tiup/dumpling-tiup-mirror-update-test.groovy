def tiup_desc = ""
def desc = "Dumpling is a CLI tool that helps you dump MySQL/TiDB data"

def dumpling_sha1, tarball_name, dir_name

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

    if (arch == "arm64" || os == "darwin") {
        tarball_name = "${name}-${os}-${arch}.tar.gz"
    } else {
        tarball_name = "${name}.tar.gz"
    }
    if (RELEASE_TAG != "nightly") {
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${dumpling_sha1}/${platform}/${tarball_name}
    """
    } else {
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/${dumpling_sha1}/${platform}/${tarball_name}
    """
    }

}

def unpack = { name, version, os, arch ->
    if (arch == "arm64" || os == "darwin") {
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
        tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz dumpling
        rm -rf bin
        """
    } else {
        sh """
        tar -C ${name}-*/bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz dumpling
        rm -rf ${name}-*
        """
    }

    sh """
    tiup mirror publish ${name} ${TIDB_VERSION} package/${name}-${version}-${os}-${arch}.tar.gz ${name} --standalone --arch ${arch} --os ${os} --desc="${desc}"
    """
}

def update = { name, version, os, arch ->
    download name, version, os, arch
    unpack name, version, os, arch
    pack name, version, os, arch
}

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

        if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
            stage("Get hash") {
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                if (RELEASE_TAG == "nightly") {
                    tag = "master"
                } else {
                    tag = RELEASE_TAG
                }

                dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            }

            stage("tiup release dumpling linux amd64") {
                update "dumpling", RELEASE_TAG, "linux", "amd64"
            }

            stage("tiup release dumpling linux arm64") {
                update "dumpling", RELEASE_TAG, "linux", "arm64"
            }

            stage("tiup release dumpling darwin amd64") {
                update "dumpling", RELEASE_TAG, "darwin", "amd64"
            }

            stage("tiup release dumpling darwin arm64") {
                update "dumpling", RELEASE_TAG, "darwin", "arm64"
            }
        }
    }
}