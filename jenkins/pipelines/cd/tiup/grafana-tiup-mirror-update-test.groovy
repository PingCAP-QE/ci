def checkoutTiCS(branch) {
    checkout(changelog: false, poll: true, scm: [
            $class: "GitSCM",
            branches: [
                    [name: "${branch}"],
            ],
            userRemoteConfigs: [
                    [
                            url: "git@github.com:pingcap/tics.git",
                            refspec: "+refs/heads/*:refs/remotes/origin/*",
                            credentialsId: "github-sre-bot-ssh",
                    ]
            ],
            extensions: [
                    [$class: 'SubmoduleOption',
                     disableSubmodules: true,
                     parentCredentials: true,
                     recursiveSubmodules: false,
                     trackingSubmodules: false,
                     reference: ''],
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
                    [$class: 'LocalBranch']
            ],
            doGenerateSubmoduleConfigurations: false,
    ])
    // checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${branch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/heads/*:refs/remotes/origin/*", url: 'git@github.com:pingcap/tics.git']]]
}

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

def download = { version, os, arch ->
    sh """
    wget -qnc https://download.pingcap.org/grafana-${version}.${os}-${arch}.tar.gz
    """
}

def unpack = { version, os, arch ->
    sh """
    tar -zxf grafana-${version}.${os}-${arch}.tar.gz
    """
}

def pack = { version, os, arch ->
    def tag = RELEASE_TAG
    if(tag == "nightly") {
        tag = "master"
    }

    sh """
    cd "grafana-${version}"
    if [ ${tag} == "master" ] || [[ ${tag} > "v4" ]];then \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/metrics/grafana/tidb.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/metrics/grafana/tidb_summary.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/metrics/grafana/overview.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/metrics/grafana/tidb_runtime.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/pd/${tag}/metrics/grafana/pd.json || true; \
    
    wget -qnc https://github.com/tikv/tikv/archive/${tag}.zip
    unzip ${tag}.zip
    rm -rf ${tag}.zip
    cp tikv-*/metrics/grafana/*.json .
    rm -rf tikv-*

    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-binlog/${tag}/metrics/grafana/binlog.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/ticdc/${tag}/metrics/grafana/ticdc.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/grafana/disk_performance.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/grafana/blackbox_exporter.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/grafana/node.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/grafana/kafka.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-lightning/${tag}/metrics/grafana/lightning.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/br/${tag}/metrics/grafana/br.json || true; \
    cp ../metrics/grafana/* . || true; \
    else \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/tidb.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/tidb_summary.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/overview.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/pd.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/tikv_summary.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/tikv_details.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/tikv_trouble_shooting.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/performance_read.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/performance_write.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/binlog.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/disk_performance.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/blackbox_exporter.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/node.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/kafka.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/lightning.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/tiflash_proxy_summary.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/tiflash_summary.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/scripts/tiflash_proxy_details.json || true; \
    fi

    cd ..
    tiup package . -C grafana-${version} --hide --arch ${arch} --os "${os}" --desc 'Grafana is the open source analytics & monitoring solution for every database' --entry "bin/grafana-server" --name grafana --release "${RELEASE_TAG}"
    tiup mirror publish grafana ${TIDB_VERSION} package/grafana-${RELEASE_TAG}-${os}-${arch}.tar.gz "bin/grafana-server" --arch ${arch} --os ${os} --desc="Grafana is the open source analytics & monitoring solution for every database"
    rm -rf grafana-${version}
    """
}

def upload = { dir ->
    sh """
    rm -rf ~/.qshell/qupload
    qshell qupload2 --src-dir=${dir} --bucket=tiup-mirrors --overwrite
    """
}

def update = { version, os, arch ->
    try {
        sh """
        rm -rf ./grafana*
        """
        download version, os, arch
        unpack version, os, arch
        pack version, os, arch
    } catch(e) {
        echo "update grafana-${version}-${os}-${arch}: ${e}"
    }
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

        stage("Checkout tics") {
            def tag = RELEASE_TAG
            if(tag == "nightly") {
                tag = "master"
            }
            if(tag == "master" || tag > "v4") {
                checkoutTiCS(tag)
            }
        }

        stage("tiup build grafana on linux/amd64") {
            update VERSION, "linux", "amd64"
        }

        stage("TiUP build grafana on linux/arm64") {
            update VERSION, "linux", "arm64"
        }

        stage("TiUP build grafana on darwin/amd64") {
            update VERSION, "darwin", "amd64"
        }

        // stage("Upload") {
        //     upload "package"
        // }
    }
}