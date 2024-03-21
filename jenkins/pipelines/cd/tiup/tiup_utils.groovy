def install_tiup(bin_dir,private_key) {
    sh """
    wget -qO tiup-linux-amd64.tar.gz https://tiup-mirrors.pingcap.com/tiup-v1.14.1-linux-amd64.tar.gz
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

def install_tiup_without_key(bin_dir) {
    sh """
    wget -qO tiup-linux-amd64.tar.gz ${TIUP_MIRROR}/tiup-v1.14.1-linux-amd64.tar.gz
    tar -zxf tiup-linux-amd64.tar.gz -C ${bin_dir}
    chmod 755 ${bin_dir}/tiup
    rm -rf ~/.tiup
    mkdir -p ~/.tiup/bin
    curl ${TIUP_MIRROR}/root.json -o ~/.tiup/bin/root.json
    mkdir -p ~/.tiup/keys
    """
}

return this
