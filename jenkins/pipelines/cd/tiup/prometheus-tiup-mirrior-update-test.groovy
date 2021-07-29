def checkoutTiCS(branch) {
    checkout(changelog: false, poll: true, scm: [
            $class                           : "GitSCM",
            branches                         : [
                    [name: "${branch}"],
            ],
            userRemoteConfigs                : [
                    [
                            url          : "git@github.com:pingcap/tics.git",
                            refspec      : "+refs/heads/*:refs/remotes/origin/*",
                            credentialsId: "github-sre-bot-ssh",
                    ]
            ],
            extensions                       : [
                    [$class             : 'SubmoduleOption',
                     disableSubmodules  : true,
                     parentCredentials  : true,
                     recursiveSubmodules: true,
                     trackingSubmodules : false,
                     reference          : ''],
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
                    [$class: 'LocalBranch']
            ],
            doGenerateSubmoduleConfigurations: false,
    ])
    // checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${branch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/heads/*:refs/remotes/origin/*", url: 'git@github.com:pingcap/tics.git']]]
}

def download = { version, os, arch ->
    sh """
    wget -qnc https://download.pingcap.org/prometheus-${version}.${os}-${arch}.tar.gz
    """
}

def unpack = { version, os, arch ->
    sh """
    tar -zxf prometheus-${version}.${os}-${arch}.tar.gz
    """
}

def pack = { version, os, arch ->
    def tag = RELEASE_TAG
    if (tag == "nightly") {
        tag = "master"
    }
    sh """
    mv prometheus-${version}.${os}-${arch} prometheus
    cd prometheus
    if [ ${tag} == "master" ] || [[ ${tag} > "v4" ]];then \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/metrics/alertmanager/tidb.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/pd/${tag}/metrics/alertmanager/pd.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/tikv/tikv/${tag}/metrics/alertmanager/tikv.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/tikv/tikv/${tag}/metrics/alertmanager/tikv.accelerate.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-binlog/${tag}/metrics/alertmanager/binlog.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/ticdc/${tag}/metrics/alertmanager/ticdc.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/br/${tag}/metrics/alertmanager/lightning.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/rule/blacker.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/rule/bypass.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/rule/kafka.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/rule/node.rules.yml || true; \
    cp ../metrics/alertmanager/tiflash.rules.yml . || true; \
    else \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/roles/prometheus/files/tidb.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/roles/prometheus/files/pd.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/roles/prometheus/files/tikv.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/roles/prometheus/files/tikv.accelerate.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/roles/prometheus/files/binlog.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/roles/prometheus/files/lightning.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/roles/prometheus/files/blacker.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/roles/prometheus/files/bypass.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/roles/prometheus/files/kafka.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/roles/prometheus/files/node.rules.yml|| true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${tag}/roles/prometheus/files/tiflash.rules.yml || true; \
    fi

    cd ..

    tiup package "prometheus" --hide --arch ${arch} --os "${os}" --desc "The Prometheus monitoring system and time series database." --entry "prometheus/prometheus" --name prometheus --release "${RELEASE_TAG}"
    tiup mirror publish prometheus ${TIDB_VERSION} package/prometheus-${RELEASE_TAG}-${os}-${arch}.tar.gz "prometheus/prometheus" --arch ${arch} --os ${os} --desc="The Prometheus monitoring system and time series database"
    rm -rf prometheus
    """
}

def update = { version, os, arch ->
    download version, os, arch
    unpack version, os, arch
    pack version, os, arch
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

        stage("Checkout tics") {
            def tag = RELEASE_TAG
            if (tag == "nightly") {
                tag = "master"
            }
            if (tag == "master" || tag > "v4") {
                checkoutTiCS(tag)
            }
        }

        stage("TiUP build prometheus on linux/amd64") {
            update VERSION, "linux", "amd64"
        }

        stage("TiUP build prometheus on linux/arm64") {
            update VERSION, "linux", "arm64"
        }

        stage("TiUP build prometheus on darwin/amd64") {
            update VERSION, "darwin", "amd64"
        }

        stage("TiUP build prometheus on darwin/arm64") {
            update VERSION, "darwin", "arm64"
        }
    }
}