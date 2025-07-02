
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
    // rm node cache for tiup.
    if (os == "darwin" && arch == "arm64") {
        sh "rm -rf grafana-${version}/plugins-bundled/internal/input-datasource/node_modules"
    }
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
    wget -qnc https://raw.githubusercontent.com/pingcap/tidb/${tag}/metrics/grafana/tidb_resource_control.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/pd/${tag}/metrics/grafana/pd.json || true;

    wget -qnc https://github.com/tikv/tikv/archive/${tag}.zip
    unzip -q ${tag}.zip
    rm -rf ${tag}.zip
    cp tikv-*/metrics/grafana/*.json .
    rm -rf tikv-*

    wget -qnc https://raw.githubusercontent.com/pingcap/tidb-binlog/${tag}/metrics/grafana/binlog.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tiflow/${tag}/metrics/grafana/ticdc.json || true; \
    wget -qnc https://raw.githubusercontent.com/pingcap/tiflow/${tag}/metrics/grafana/TiCDC-Monitor-Summary.json || true; \

    wget -qnc https://raw.githubusercontent.com/tikv/migration/main/cdc/metrics/grafana/tikv-cdc.json|| true; \

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
    wget -qnc https://raw.githubusercontent.com/pingcap/tiflash/${tag}/metrics/grafana/tiflash_proxy_details.json
    wget -qnc https://raw.githubusercontent.com/pingcap/tiflash/${tag}/metrics/grafana/tiflash_proxy_summary.json
    wget -qnc https://raw.githubusercontent.com/pingcap/tiflash/${tag}/metrics/grafana/tiflash_summary.json

    cd ..
    mkdir -p package
    tar -C grafana-${version} -czvf package/grafana-${RELEASE_TAG}-${os}-${arch}.tar.gz .
    tiup mirror publish grafana ${TIDB_VERSION} package/grafana-${RELEASE_TAG}-${os}-${arch}.tar.gz "bin/grafana-server" --arch ${arch} --os ${os} --desc="Grafana is the open source analytics & monitoring solution for every database"
    rm -rf grafana-${version}
    """
}

def update = { version, os, arch ->
    sh 'rm -rf ./grafana*'
    download version, os, arch
    unpack version, os, arch
    pack version, os, arch

}

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes"
    def namespace = "jenkins-cd"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
    def jnlp_docker_image = "jenkins/inbound-agent:3148.v532a_7e715ee3-10"
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
