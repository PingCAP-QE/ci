

def name="ng-monitoring"
def ng_monitoring_sha1

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
    def platform = ""
    if (os == "linux") {
        platform = "centos7"
    }

    if (os == "darwin") {
        platform = "darwin"
    }

    if (os == "darwin" && arch == "arm64") {
        platform = "darwin-arm64"
    }

    def tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    rm -rf ${tarball_name}
    """

    def tag = RELEASE_TAG
    if (RELEASE_BRANCH != "") {
        tag = RELEASE_BRANCH
    }

    if ( RELEASE_TAG >="v5.3.0" || RELEASE_TAG =="nightly" ) {
        sh """
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${RELEASE_TAG}/${ng_monitoring_sha1}/${platform}/${tarball_name}
        """
    }

}

def unpack = { version, os, arch ->
    def tarball_name = "${name}-${os}-${arch}.tar.gz"
    sh """
    tar -zxf prometheus-${version}.${os}-${arch}.tar.gz
    """
    if ( RELEASE_TAG >="v5.3.0" || RELEASE_TAG =="nightly" ) {
        sh """
            rm -rf ng-monitoring-${RELEASE_TAG}-${os}-${arch}
            tar -zxf ${tarball_name}
        """
    }
}

def pack = { version, os, arch ->
    def tag = RELEASE_TAG
    if (RELEASE_BRANCH != "") {
        tag = RELEASE_BRANCH
    }

    sh """
    mv prometheus-${version}.${os}-${arch} prometheus
    if [ ${RELEASE_TAG} \\> "v5.3.0" ] || [ ${RELEASE_TAG} == "v5.3.0" ] || [ ${RELEASE_TAG} == "nightly" ] ; then \
       cp bin/* ./
       rm -rf ng-monitoring-${RELEASE_TAG}-${os}-${arch}
    fi
    cd prometheus
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/metrics/alertmanager/tidb.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/pd/${tag}/metrics/alertmanager/pd.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/tikv/tikv/${tag}/metrics/alertmanager/tikv.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/tikv/tikv/${tag}/metrics/alertmanager/tikv.accelerate.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-binlog/${tag}/metrics/alertmanager/binlog.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tiflow/${tag}/metrics/alertmanager/ticdc.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tiflash/${tag}/metrics/alertmanager/tiflash.rules.yml || true; \
    
    if [ ${RELEASE_TAG} \\> "v5.2.0" ] || [ ${RELEASE_TAG} == "v5.2.0" ]; then \
        wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/br/metrics/alertmanager/lightning.rules.yml || true; \
    else
        wget -qnc https://raw.githubusercontent.com/pingcap/br/${tag}/metrics/alertmanager/lightning.rules.yml || true; \
    fi

    wget -qnc https://raw.githubusercontent.com/pingcap/br/${tag}/metrics/alertmanager/lightning.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/rule/blacker.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/rule/bypass.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/rule/kafka.rules.yml || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/monitoring/master/platform-monitoring/ansible/rule/node.rules.yml || true;


    cd ..

    #tiup package "prometheus" --hide --arch ${arch} --os "${os}" --desc "The Prometheus monitoring system and time series database." --entry "prometheus/prometheus" --name prometheus --release "${RELEASE_TAG}"
    rm -rf package
    mkdir -p package
    if [ ${RELEASE_TAG} \\> "v5.3.0" ] || [ ${RELEASE_TAG} == "v5.3.0" ] || [ ${RELEASE_TAG} == "nightly" ] ; then \
        tar czvf package/prometheus-${RELEASE_TAG}-${os}-${arch}.tar.gz prometheus ng-monitoring-server
    else
        tar czvf package/prometheus-${RELEASE_TAG}-${os}-${arch}.tar.gz prometheus
    fi
    tiup mirror publish prometheus ${TIDB_VERSION} package/prometheus-${RELEASE_TAG}-${os}-${arch}.tar.gz "prometheus/prometheus" --arch ${arch} --os ${os} --desc="The Prometheus monitoring system and time series database"
    rm -rf prometheus
    """
}

def update = { version, os, arch ->
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
            nodeSelector: "kubernetes.io/arch=amd64",
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

run_with_pod {
    container("golang") {
        stage("Prepare") {
            deleteDir()
        }
        retry(5) {
            sh """
            wget -qnc https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/cd/tiup/tiup_utils.groovy
            """
        }
        
        def util = load "tiup_utils.groovy"

        stage("Install tiup") {
            util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
        }

        def tag = RELEASE_TAG
        if (RELEASE_BRANCH != "") {
            tag = RELEASE_BRANCH
        }
        
        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
        ng_monitoring_sha1 = ""
        if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v5.3.0") {
            if (RELEASE_BRANCH == "master"){
                ng_monitoring_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ng-monitoring -version=main -s=${FILE_SERVER_URL}").trim()
            } else {
                ng_monitoring_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ng-monitoring -version=${tag} -s=${FILE_SERVER_URL}").trim()
            }
        }

        if (RELEASE_TAG >="v5.3.0" || RELEASE_TAG =="nightly" ) {
            VERSION = "2.27.1"
        }

        multi_os_update = [:]
        if (params.ARCH_X86) {
            multi_os_update["linux/amd64"] = {
                run_with_pod {
                    container("golang") {
                        util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
                        update VERSION, "linux", "amd64"
                    }
                }
            }
        }
        if (params.ARCH_ARM) {
            multi_os_update["linux/arm64"] = {
                run_with_pod {
                    container("golang") {
                        util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
                        update VERSION, "linux", "arm64"
                    }
                }
            }
        }
        if (params.ARCH_MAC) {
            multi_os_update["darwin/amd64"] = {
                run_with_pod {
                    container("golang") {
                        util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
                        update VERSION, "darwin", "amd64"
                    }
                }
            }
        }
        if (params.ARCH_MAC_ARM) {
            if (RELEASE_TAG >="v5.1.0" || RELEASE_TAG =="nightly") {
                multi_os_update["darwin/arm64"] = {
                    run_with_pod {
                        container("golang") {
                            util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
                            // prometheus did not provide the binary we need so we upgrade it.
                            update "2.28.1", "darwin", "arm64"
                        }
                    }
                }
            }
        }
        parallel multi_os_update
    }
}
