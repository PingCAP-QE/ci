def checkoutTiflash(branch) {
    checkout(changelog: false, poll: true, scm: [
            $class                           : "GitSCM",
            branches                         : [
                    [name: "${branch}"],
            ],
            userRemoteConfigs                : [
                    [
                            url          : "git@github.com:pingcap/tiflash.git",
                            refspec      : "+refs/heads/*:refs/remotes/origin/*",
                            credentialsId: "github-sre-bot-ssh",
                    ]
            ],
            extensions                       : [
                    [$class             : 'SubmoduleOption',
                     disableSubmodules  : true,
                     parentCredentials  : true,
                     recursiveSubmodules: false,
                     trackingSubmodules : false,
                     reference          : ''],
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
                    [$class: 'LocalBranch']
            ],
            doGenerateSubmoduleConfigurations: false,
    ])
}

def download = { version, os, arch ->
    if (os == "darwin" && arch == "arm64") {
        sh """
        curl -O ${FILE_SERVER_URL}/download/pingcap/grafana-${version}.${os}-${arch}.tar.gz
        """
    }else {
        sh """
        wget -qnc https://download.pingcap.org/grafana-${version}.${os}-${arch}.tar.gz
        """
    }
}

def unpack = { version, os, arch ->
    sh """
    tar -zxf grafana-${version}.${os}-${arch}.tar.gz
    """
}

def pack = { version, os, arch ->
    def tag = RELEASE_TAG
    if (RELEASE_BRANCH != "") {
        tag = RELEASE_BRANCH
    }

    sh """
    cd "grafana-${version}"
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/metrics/grafana/tidb.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/metrics/grafana/tidb_summary.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/metrics/grafana/overview.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/metrics/grafana/performance_overview.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/metrics/grafana/tidb_runtime.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/pd/${tag}/metrics/grafana/pd.json || true; \
    wget -qnc https://github.com/tikv/tikv/archive/${tag}.zip
    unzip ${tag}.zip
    rm -rf ${tag}.zip
    cp tikv-*/metrics/grafana/*.json .
    rm -rf tikv-*
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-binlog/${tag}/metrics/grafana/binlog.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tiflow/${tag}/metrics/grafana/ticdc.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/grafana/disk_performance.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/grafana/blackbox_exporter.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/grafana/node.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/grafana/kafka.json || true; \
    if [ ${RELEASE_TAG} \\> "v5.2.0" ] || [ ${RELEASE_TAG} == "v5.2.0" ]; then \
        wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/br/metrics/grafana/lightning.json || true; \
        wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/br/metrics/grafana/br.json || true; \
    else
        wget -qnc https://raw.githubusercontent.com/pingcap/br/${tag}/metrics/grafana/lightning.json || true; \
        wget -qnc https://raw.githubusercontent.com/pingcap/br/${tag}/metrics/grafana/br.json || true; \
    fi
    cp ../metrics/grafana/* . || true;
    cd ..
    tiup package . -C grafana-${version} --hide --arch ${arch} --os "${os}" --desc 'Grafana is the open source analytics & monitoring solution for every database' --entry "bin/grafana-server" --name grafana --release "${RELEASE_TAG}"
    tiup mirror publish grafana ${TIDB_VERSION} package/grafana-${RELEASE_TAG}-${os}-${arch}.tar.gz "bin/grafana-server" --arch ${arch} --os ${os} --desc="Grafana is the open source analytics & monitoring solution for every database"
    rm -rf grafana-${version}
    """
}

def update = { version, os, arch ->
    sh """
        rm -rf ./grafana*
        """
    download version, os, arch
    unpack version, os, arch
    pack version, os, arch

}

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes"
    def namespace = "jenkins-cd"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: true,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                            
                    )
            ],
            volumes: [
                            emptyDirVolume(mountPath: '/tmp', memory: false),
                            emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
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

        stage("Checkout tiflash") {
            def tag = RELEASE_TAG
            if (RELEASE_BRANCH != "") {
                tag = RELEASE_BRANCH
            }
            checkoutTiflash(tag)
        }

        multi_os_update = [:]
        if (params.ARCH_X86) {
            multi_os_update["tiup build grafana on linux/amd64"] = {
                run_with_pod {
                    container("golang") {
                        util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
                        update VERSION, "linux", "amd64"
                    }
                }
            }
        }
        if (params.ARCH_ARM) {
            multi_os_update["TiUP build grafana on linux/arm64"] = {
                run_with_pod {
                    container("golang") {
                        util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
                        update VERSION, "linux", "arm64"
                    }
                }
            }
        }
        if (params.ARCH_MAC) {
            multi_os_update["TiUP build grafana on darwin/amd64"] = {
                run_with_pod {
                    container("golang") {
                        util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
                        update VERSION, "darwin", "amd64"
                    }
                }
            }
        }
        if (params.ARCH_MAC_ARM) {
            multi_os_update["TiUP build grafana on darwin/arm64"] = {
                run_with_pod {
                    container("golang") {
                        util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
                        // grafana did not provide the binary we need so we upgrade it.
                        update "7.5.10", "darwin", "arm64"
                    }
                }
            }
        }
        parallel multi_os_update
    }
}