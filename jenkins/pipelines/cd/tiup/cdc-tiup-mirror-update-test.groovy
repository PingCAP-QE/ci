def ticdc_sha1, platform, tag
def cdc_desc = "CDC is a change data capture tool for TiDB"

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

    sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${tag}/${hash}/${platform}/${name}-${os}-${arch}.tar.gz
    """
}

def unpack = { name, os, arch ->
    sh """
    tar -zxf ${name}-${os}-${arch}.tar.gz
    """
}

def pack = { name, version, os, arch ->

    sh """
    # tiup package cdc -C ${name}-${os}-${arch}/bin --hide --name=cdc --release=${version} --entry=cdc --os=${os} --arch=${arch} --desc="${cdc_desc}"
    [ -d package ] || mkdir package
    tar -C bin -czvf package/cdc-${version}-${os}-${arch}.tar.gz cdc
    tiup mirror publish cdc ${TIDB_VERSION} package/cdc-${version}-${os}-${arch}.tar.gz cdc --arch ${arch} --os ${os} --desc="${cdc_desc}"
    """
}

def update = { name, version, hash, os, arch ->
    download name, hash, os, arch
    unpack name, os, arch
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

                ticdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tiflow -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            }

            stage("TiUP build cdc on linux/amd64") {
                update "ticdc", RELEASE_TAG, ticdc_sha1, "linux", "amd64"
            }

            stage("TiUP build cdc on linux/arm64") {
                update "ticdc", RELEASE_TAG, ticdc_sha1, "linux", "arm64"
            }

            stage("TiUP build cdc on darwin/amd64") {
                update "ticdc", RELEASE_TAG, ticdc_sha1, "darwin", "amd64"
            }
            if (RELEASE_TAG >="v5.1.0" || RELEASE_TAG =="nightly") {
                stage("TiUP build cdc on darwin/arm64") {
                    update "ticdc", RELEASE_TAG, ticdc_sha1, "darwin", "arm64"
                }
            }   
        }
    }
}