def install_tiup(bin_dir,private_key) {
    sh """
    wget -q https://tiup-mirrors.pingcap.com/tiup-linux-amd64.tar.gz
    sudo tar -zxf tiup-linux-amd64.tar.gz -C ${bin_dir}
    sudo chmod 755 ${bin_dir}/tiup
    rm -rf ~/.tiup
    mkdir -p /home/jenkins/.tiup/bin/
    curl https://tiup-mirrors.pingcap.com/root.json -o /home/jenkins/.tiup/bin/root.json
    mkdir -p ~/.tiup/keys
    set +x
    echo ${private_key} | base64 -d > ~/.tiup/keys/private.json
    set -x
    """
}

def install_qshell(bin_dir,qshell_key,qshell_sec) {
    sh """
    wget -q https://tiup-mirrors.pingcap.com/qshell-linux-amd64.tar.gz
    sudo tar -zxf qshell-linux-amd64.tar.gz -C ${bin_dir}
    sudo chmod 755 ${bin_dir}/qshell
    set +x
    qshell account ${qshell_key} ${qshell_sec} tiup-mirror-update --overwrite
    set -x
    """
}

def download(name, version, os, arch) {
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
    if (RELEASE_TAG != "nightly") {
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${tag}/${br_sha1}/${platform}/${tarball_name}
    """
    } else {
        sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/${tag}/${br_sha1}/${platform}/${tarball_name}
    """
    }
}

def unpack(name, version, os, arch) {
    if (arch == "arm64") {
        tarball_name = "${name}-${os}-${arch}.tar.gz"
    } else {
        tarball_name = "${name}.tar.gz"
    }

    sh """
    tar -zxf ${tarball_name}
    """
}

def pack(name, version, os, arch, tidb_version) {

    sh """
    rm -rf ${name}*.tar.gz
    [ -d package ] || mkdir package
    """

    if (os == "linux" && arch == "amd64") {
        sh """
        tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz br
        rm -rf bin
        """
    } else {
        sh """
        tar -C ${name}-*/bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz br
        rm -rf ${name}-*
        """
    }

    sh """
    tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name} --standalone --arch ${arch} --os ${os} --desc="${br_desc}"
    """
}

def upload(dir) {
    sh """
    rm -rf ~/.qshell/qupload
    qshell qupload2 --src-dir=${dir} --bucket=tiup-mirrors --overwrite
    """
}

def update(name, version, os, arch, TIDB_VERSION) {
    download name, version, os, arch
    unpack name, version, os, arch
    pack name, version, os, arch, TIDB_VERSION
}

return this