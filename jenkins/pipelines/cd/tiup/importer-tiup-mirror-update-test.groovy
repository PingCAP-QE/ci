def tiup_desc = ""
def desc = ""

def tiflash_sha1, tarball_name, dir_name

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

def download = { name, version, os, arch ->
    if(os == "linux") {
        platform = "centos7"
    } else if(os == "darwin") {
        platform = "darwin"
    } else {
        sh """
        exit 1
        """
    }

    if(arch == "arm64") {
        tarball_name = "${name}-${os}-${arch}.tar.gz"
    } else {
        tarball_name = "${name}.tar.gz"
    }

    sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/${importer_sha1}/${platform}/${tarball_name}
    """
}

def unpack = { name, version, os, arch ->
    if(arch == "arm64") {
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

    if(os == "linux" && arch == "amd64") {
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

def upload = { dir ->
    sh """
    rm -rf ~/.qshell/qupload
    qshell qupload2 --src-dir=${dir} --bucket=tiup-mirrors --overwrite
    """
}

def update = { name, version, os, arch ->
    try {
        download name, version, os, arch
        unpack name, version, os, arch
        pack name, version, os, arch
    } catch(e) {
        echo "update tikv-${name}-${version}-${os}-${arch}: ${e}"
    }
}

try{
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
            if(RELEASE_TAG != "nightly" && RELEASE_TAG < "v4.0.0") {
                stage("Get hash") {
                    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                    if(RELEASE_TAG == "nightly") {
                        tag = "master"
                    } else {
                        tag = RELEASE_TAG
                    }

                    if(TIDB_VERSION == "") {
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
            }

            // upload "package"
        }
    }
} catch (Exception e) {
    echo "${e}"
}