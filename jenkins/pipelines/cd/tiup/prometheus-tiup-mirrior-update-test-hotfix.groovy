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

def name="ng-monitoring"
def ng_monitoring_sha1, tarball_name

def get_hash = { hash_or_branch, repo ->
    if (hash_or_branch.length() == 40) {
        return hash_or_branch
    }
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${hash_or_branch} -s=${FILE_SERVER_URL}").trim()
}

def download = { version, os, arch ->
    if (os == "darwin" && arch == "arm64") {
        sh """
        curl -O ${FILE_SERVER_URL}/download/pingcap/prometheus-${version}.${os}-${arch}.tar.gz
        """
    }else {
        sh """
        wget -qnc https://download.pingcap.org/prometheus-${version}.${os}-${arch}.tar.gz
        """
    }
    if (os == "linux") {
        platform = "centos7"
    } else if (os == "darwin" && arch == "amd64") {
        platform = "darwin"
    } else if (os == "darwin" && arch == "arm64") {
        platform = "darwin-arm64"
    }

    if (arch == "arm64" && os != "darwin") {
        tarball_name = "${name}-${os}-${arch}.tar.gz"
    } else {
        tarball_name = "${name}.tar.gz"
    }

    if (arch != "arm64") {

        if (HOTFIX_TAG != "nightly" && HOTFIX_TAG >= "v5.3.0") {
            sh """
                wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization//${ng_monitoring_sha1}/${platform}/${tarball_name}
            """
        } else if (HOTFIX_TAG == "nightly") {
            sh """
                wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/${ng_monitoring_sha1}/${platform}/${tarball_name}
            """
        }
    }
}

def unpack = { version, os, arch ->
    sh """
    tar -zxf prometheus-${version}.${os}-${arch}.tar.gz
    """
    if (arch != "arm64"  && HOTFIX_TAG >="v5.3.0" || HOTFIX_TAG =="nightly" ) {
        sh """
            tar -zxf ng-monitoring-${os}-${arch}.tar.gz
        """
    }
}

def pack = { version, os, arch ->
    def tag = HOTFIX_TAG
    if (tag == "nightly") {
        tag = "master"
    }
    sh """
    mv prometheus-${version}.${os}-${arch} prometheus
    if [ ${arch} == "amd64" ] && [ ${HOTFIX_TAG} \\> "v5.2.0" ] || [ ${HOTFIX_TAG} == "v5.2.0" ]; then \
       cp ng-monitoring-${version}-${os}-${arch}/bin/* prometheus/prometheus
    fi
    cd prometheus
    if [ ${tag} == "master" ] || [[ ${tag} > "v4" ]];then \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${RELEASE_BRANCH}/metrics/alertmanager/tidb.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/pd/${RELEASE_BRANCH}/metrics/alertmanager/pd.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/tikv/tikv/${RELEASE_BRANCH}/metrics/alertmanager/tikv.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/tikv/tikv/${RELEASE_BRANCH}/metrics/alertmanager/tikv.accelerate.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-binlog/${RELEASE_BRANCH}/metrics/alertmanager/binlog.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/ticdc/${RELEASE_BRANCH}/metrics/alertmanager/ticdc.rules.yml || true; \
    if [ ${HOTFIX_TAG} \\> "v5.2.0" ] || [ ${HOTFIX_TAG} == "v5.2.0" ]; then \
        wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${RELEASE_BRANCH}/br/metrics/alertmanager/lightning.rules.yml || true; \
    else
        wget -qnc https://raw.githubusercontent.com/pingcap/br/${RELEASE_BRANCH}/metrics/alertmanager/lightning.rules.yml || true; \
    fi
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/rule/blacker.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/rule/bypass.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/rule/kafka.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/rule/node.rules.yml || true; \
    cp ../metrics/alertmanager/tiflash.rules.yml . || true; \
    else \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${RELEASE_BRANCH}/roles/prometheus/files/tidb.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${RELEASE_BRANCH}/roles/prometheus/files/pd.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${RELEASE_BRANCH}/roles/prometheus/files/tikv.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${RELEASE_BRANCH}/roles/prometheus/files/tikv.accelerate.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${RELEASE_BRANCH}/roles/prometheus/files/binlog.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${RELEASE_BRANCH}/roles/prometheus/files/lightning.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${RELEASE_BRANCH}/roles/prometheus/files/blacker.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${RELEASE_BRANCH}/roles/prometheus/files/bypass.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${RELEASE_BRANCH}/roles/prometheus/files/kafka.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${RELEASE_BRANCH}/roles/prometheus/files/node.rules.yml|| true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-ansible/${RELEASE_BRANCH}/roles/prometheus/files/tiflash.rules.yml || true; \
    fi

    cd ..

    tiup package "prometheus" --hide --arch ${arch} --os "${os}" --desc "The Prometheus monitoring system and time series database." --entry "prometheus/prometheus" --name prometheus --release "${HOTFIX_TAG}"
    tiup mirror publish prometheus ${TIDB_VERSION} package/prometheus-${HOTFIX_TAG}-${os}-${arch}.tar.gz "prometheus/prometheus" --arch ${arch} --os ${os} --desc="The Prometheus monitoring system and time series database"
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
            def tag = HOTFIX_TAG
            if (tag == "nightly") {
                tag = "master"
            }
            if (tag == "master" || tag > "v4") {
                checkoutTiCS(RELEASE_BRANCH)
            }
        }
        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

        ng_monitoring_sha1 = ""
        if (HOTFIX_TAG == "nightly" || HOTFIX_TAG >= "v5.3.0") {
            ng_monitoring_sha1 = get_hash(ORIGIN_TAG,"ng-monitoring")
        }

        if (HOTFIX_TAG >="v5.3.0" || HOTFIX_TAG =="nightly" ) {
            VERSION = "2.27.1"
        }
        if (params.ARCH_X86) {
            stage("TiUP build prometheus on linux/amd64") {
                update VERSION, "linux", "amd64"
            }
        }
        if (params.ARCH_ARM) {
            stage("TiUP build prometheus on linux/arm64") {
                update VERSION, "linux", "arm64"
            }
        }
        if (params.ARCH_MAC) {
            stage("TiUP build prometheus on darwin/amd64") {
                update VERSION, "darwin", "amd64"
            }
        }
        if (params.ARCH_MAC_ARM) {
            stage("TiUP build prometheus on darwin/arm64") {
                // prometheus did not provide the binary we need so we upgrade it.
                update "2.28.1", "darwin", "arm64"
            }
        }
    }
}
