/*
* @RELEASE_TAG
* @TIUP_MIRRORS
* @TIDB_VERSION
*/

def dm_master_desc = "dm-master component of Data Migration Platform"
def dm_worker_desc = "dm-worker component of Data Migration Platform"
def dmctl_desc = "dmctl component of Data Migration Platform"

def dm_sha1, tarball_name, dir_name

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


    tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/${name}/${dm_sha1}/${platform}/${tarball_name} | tar xz
    curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/${name}/${dm_sha1}/${platform}/dm-ansible.tar.gz | tar xz
    """
}

def pack = { name, version, os, arch ->

    sh """
    [ -d package ] || mkdir package
    """

    sh """
    echo "package dm-master"
    mkdir ${name}-master
    mkdir ${name}-master/conf
    mkdir ${name}-master/scripts
    cp ${name}-${os}-${arch}/bin/dm-master ${name}-master
    cp -r dm-ansible/conf/* ${name}-master/conf
    cp dm-ansible/scripts/* ${name}-master/scripts
    tar -czvf package/${name}-master-${version}-${os}-${arch}.tar.gz ${name}-master
    rm -rf ${name}-master

    echo "package dm-worker"
    mkdir ${name}-worker
    mkdir ${name}-worker/conf
    mkdir ${name}-worker/scripts
    cp ${name}-${os}-${arch}/bin/dm-worker ${name}-worker
    cp -r dm-ansible/conf/* ${name}-worker/conf
    cp dm-ansible/scripts/* ${name}-worker/scripts
    tar -czvf package/${name}-worker-${version}-${os}-${arch}.tar.gz ${name}-worker
    rm -rf ${name}-worker

    echo "package dmctl"
    mkdir ${name}ctl
    mkdir ${name}ctl/conf
    mkdir ${name}ctl/scripts
    cp ${name}-${os}-${arch}/bin/dmctl ${name}ctl
    cp -r dm-ansible/conf/* ${name}ctl/conf
    cp dm-ansible/scripts/* ${name}ctl/scripts
    tar -czvf package/${name}ctl-${version}-${os}-${arch}.tar.gz ${name}ctl
    rm -rf ${name}ctl
    """

    sh """
    tiup mirror publish ${name}-master ${TIDB_VERSION} package/${name}-master-${version}-${os}-${arch}.tar.gz ${name}-master/${name}-master --arch ${arch} --os ${os} --desc="${dm_master_desc}"
    tiup mirror publish ${name}-worker ${TIDB_VERSION} package/${name}-worker-${version}-${os}-${arch}.tar.gz ${name}-worker/${name}-worker --arch ${arch} --os ${os} --desc="${dm_worker_desc}"
    tiup mirror publish ${name}ctl ${TIDB_VERSION} package/${name}ctl-${version}-${os}-${arch}.tar.gz ${name}ctl/${name}ctl --arch ${arch} --os ${os} --desc="${dmctl_desc}"
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
        pack name, version, os, arch
    } catch(e) {
        echo "update ${name}-${version}-${os}-${arch}: ${e}"
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
            }

            stage("Get hash") {
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                dm_sha1 = ""
                if (RELEASE_TAG.startsWith("v") && RELEASE_TAG >= "v5.3.0" ){
                    dm_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tiflow -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                } else {
                    dm_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=dm -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                }
            }

            stage("tiup release dm linux amd64") {
                update "dm", RELEASE_TAG, "linux", "amd64"
            }

            stage("tiup release dm linux arm64") {
                update "dm", RELEASE_TAG, "linux", "arm64"
            }
        }
    }
} catch (Exception e) {
    echo "${e}"
    throw e
}
