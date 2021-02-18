def ticdc_sha1, platform, tag
def cdc_desc = "CDC is a change data capture tool for TiDB"

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
    if (RELEASE_TAG != "nightly" && arch == "amd64" && os == "linux") {
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${hash}/${platform}/${name}-${os}-${arch}.tar.gz
    """
    } else {
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/${hash}/${platform}/${name}-${os}-${arch}.tar.gz
    """
    }
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
    tar -C ${name}-${os}-${arch}/bin -czvf package/cdc-${version}-${os}-${arch}.tar.gz cdc
    tiup mirror publish cdc ${TIDB_VERSION} package/cdc-${version}-${os}-${arch}.tar.gz cdc --arch ${arch} --os ${os} --desc="${cdc_desc}"
    """
}

def upload = { dir ->
    sh """
    rm -rf ~/.qshell/qupload
    qshell qupload2 --src-dir=${dir} --bucket=tiup-mirrors --overwrite
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

        stage("Install tiup/qshell") {
            install_tiup "/usr/local/bin"
            install_qshell "/usr/local/bin"
        }

        if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
            stage("Get hash") {
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                if (RELEASE_TAG == "nightly") {
                    tag = "master"
                } else {
                    tag = RELEASE_TAG
                }

                ticdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ticdc -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
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

            // stage("Upload") {
            //     upload "package"
            // }
        }
    }
}