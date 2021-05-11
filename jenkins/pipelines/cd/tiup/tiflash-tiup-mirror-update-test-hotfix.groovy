def tiup_desc = ""

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
    if (HOTFIX_TAG != "nightly") {
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${tag}/${tiflash_sha1}/${platform}/${tarball_name}
    """
    } else {
        if (HOTFIX_TAG == "nightly" && arch == "amd64" && os == "linux") {
            sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/release/${tag}/${tiflash_sha1}/${platform}/${tarball_name}
    """
        }else{
            sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/${tag}/${tiflash_sha1}/${platform}/${tarball_name}
    """
        }
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
    """
}

def pack = { name, version, os, arch ->
    if (os == "linux" && arch == "amd64") {
        sh "echo pass"
    } else {
        sh "mv tiflash-${version}-${os}-${arch} tiflash"
    }

    dir("tiflash") {
        sh """
        wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf"
        md5sum "PingCAP Community Software Agreement(Chinese Version).pdf" > /tmp/chinese.check
        curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf.md5" >> /tmp/chinese.check
        md5sum --check /tmp/chinese.check

        wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf"
        md5sum "PingCAP Community Software Agreement(English Version).pdf" > /tmp/english.check
        curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf.md5" >> /tmp/english.check
        md5sum --check /tmp/english.check
        """
    }

    sh """
    tiup package tiflash --name=${name} --release=${version} --entry=${name}/${name} --os=${os} --arch=${arch} --desc="The TiFlash Columnar Storage Engine" --hide
    tiup mirror publish ${name} ${TIDB_VERSION} package/${name}-${version}-${os}-${arch}.tar.gz ${name}/${name} --arch ${arch} --os ${os} --desc="The TiFlash Columnar Storage Engine"
    rm -rf tiflash tiflash*.tar.gz
    """
}

def update = { name, version, os, arch ->
//    try {
    download name, version, os, arch
    unpack name, version, os, arch
    pack name, version, os, arch
//    } catch (e) {
//        echo "update ${name}-${version}-${os}-${arch}: ${e}"
//        currentBuild.result = "FAILURE"
//    }
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

        if (HOTFIX_TAG == "nightly" || HOTFIX_TAG >= "v3.1") {
            stage("Get hash") {
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                if (HOTFIX_TAG == "nightly") {
                    tag = "master"
                } else {
                    tag = ORIGIN_TAG
                }

                tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${ORIGIN_TAG} -s=${FILE_SERVER_URL}").trim()
            }

            if (ARCH_X86) {
                stage("tiup release tiflash linux amd64") {
                    update "tiflash", HOTFIX_TAG, "linux", "amd64"
                }
            }
            if (ARCH_ARM && (HOTFIX_TAG >= "v4.0" || HOTFIX_TAG == "nightly")) {
                stage("tiup release tiflash linux arm64") {
                    update "tiflash", HOTFIX_TAG, "linux", "arm64"
                }
            }
            if (ARCH_MAC) {
                stage("tiup release tiflash darwin amd64") {
                    update "tiflash", HOTFIX_TAG, "darwin", "amd64"
                }
            }
            // upload "package"
        }
    }
}