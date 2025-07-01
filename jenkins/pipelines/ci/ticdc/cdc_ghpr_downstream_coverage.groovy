
properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'COVERAGE_FILE',
                        trim: true
                ),
                string(
                        defaultValue: 'jenkins-ci',
                        name: 'CI_NAME',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'CI_BUILD_NUMBER',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'CI_BUILD_URL',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'CI_BRANCH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'CI_PULL_REQUEST',
                        trim: true
                ),
        ]),
])


def run_with_pod(Closure body) {
    def label = "cdc_ghpr_downstream_coverage-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tiflow"
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
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

run_with_pod {
    container("golang") {
        def ws = pwd()
        sh """
        curl -O ${COVERAGE_FILE}
        tar -xvf tiflow_coverage.tar.gz
        ls -alh
        """
        dir("go/src/github.com/pingcap/tiflow") {
            withCredentials([string(credentialsId: 'coveralls-token-tiflow', variable: 'COVERALLS_TOKEN')]) {
                timeout(30) {
                    sh """
                    export CI_NAME=${CI_NAME}
                    export CI_BUILD_NUMBER=${CI_BUILD_NUMBER}
                    export CI_BUILD_URL=${CI_BUILD_URL}
                    export CI_BRANCH=${CI_BRANCH}
                    export CI_PULL_REQUEST=${CI_PULL_REQUEST}

                    rm -rf /tmp/tidb_cdc_test
                    mkdir -p /tmp/tidb_cdc_test
                    cp cov_dir/* /tmp/tidb_cdc_test
                    set +x
                    BUILD_NUMBER=${BUILD_NUMBER} COVERALLS_TOKEN="${COVERALLS_TOKEN}" GOPATH=${ws}/go:\$GOPATH PATH=${ws}/go/bin:/go/bin:\$PATH JenkinsCI=1 make integration_test_coverage || true
                    set -x
                    """
                }
            }
        }
    }
}
