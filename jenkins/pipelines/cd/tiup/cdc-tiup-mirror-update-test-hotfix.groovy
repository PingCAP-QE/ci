/*
* @ARCH_ARM
* @ARCH_X86
* @ARCH_MAC
* @ARCH_MAC_ARM
*/

def ticdc_sha1, platform, tag
def cdc_desc = "CDC is a change data capture tool for TiDB"

def get_hash = { hash_or_branch, repo ->
    if (hash_or_branch.length() == 40) {
        return hash_or_branch
    }
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${hash_or_branch} -s=${FILE_SERVER_URL}").trim()
}

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

        if (HOTFIX_TAG == "nightly" || HOTFIX_TAG >= "v4.0.0") {
            stage("Get hash") {
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                if (HOTFIX_TAG == "nightly") {
                    tag = "master"
                } else {
                    tag = HOTFIX_TAG
                }

                ticdc_sha1 = get_hash(ORIGIN_TAG,"ticdc")
            }

            if (params.ARCH_X86) {
                stage("TiUP build cdc on linux/amd64") {
                    update "ticdc", HOTFIX_TAG, ticdc_sha1, "linux", "amd64"
                }
            }
            if (params.ARCH_ARM) {
                stage("TiUP build cdc on linux/arm64") {
                    update "ticdc", HOTFIX_TAG, ticdc_sha1, "linux", "arm64"
                }
            }
            if (params.ARCH_MAC) {
                stage("TiUP build cdc on darwin/amd64") {
                    update "ticdc", HOTFIX_TAG, ticdc_sha1, "darwin", "amd64"
                }
            }
            if (params.ARCH_MAC_ARM) {
                stage("TiUP build cdc on darwin/arm64") {
                    update "ticdc", HOTFIX_TAG, ticdc_sha1, "darwin", "arm64"
                }
            }
        }
    }
}