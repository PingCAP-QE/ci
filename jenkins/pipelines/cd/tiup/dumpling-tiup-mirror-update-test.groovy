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


    tarball_name = "${name}-${os}-${arch}.tar.gz"
 
    sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${tag}/${dumpling_sha1}/${platform}/${tarball_name}
    """

}

def unpack = { name, version, os, arch ->
    tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    tar -zxf ${tarball_name}
    """
}

def pack = { name, version, os, arch ->

    sh """
    rm -rf ${name}*.tar.gz
    [ -d package ] || mkdir package
    """

    sh """
    tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz dumpling
    rm -rf bin
    """

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
            deleteDir()
        }
        retry(5) {
            sh """
            wget -qnc https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/cd/tiup/tiup_utils.groovy
            """
        }
        
        def util = load "tiup_utils.groovy"

        stage("Install tiup") {
            util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
        }

        if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
            stage("Get hash") {
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"


                tag = RELEASE_TAG
                if(ORIGIN_TAG != "") {
                    dumpling_sha1 = ORIGIN_TAG
                } else if ( RELEASE_TAG >= "v5.3.0"){
                    dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                } else {
                    dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                }
            }

            if (params.ARCH_X86) {
                stage("tiup release dumpling linux amd64") {
                    update "dumpling", RELEASE_TAG, "linux", "amd64"
                }
            }
            if (params.ARCH_ARM) {
                stage("tiup release dumpling linux arm64") {
                    update "dumpling", RELEASE_TAG, "linux", "arm64"
                }
            }
            if (params.ARCH_MAC) {
                stage("tiup release dumpling darwin amd64") {
                    update "dumpling", RELEASE_TAG, "darwin", "amd64"
                }
            }
            if (params.ARCH_MAC_ARM && RELEASE_TAG >="v5.1.0") {
                stage("tiup release dumpling darwin arm64") {
                    update "dumpling", RELEASE_TAG, "darwin", "arm64"
                }
            }
        }
    }
}