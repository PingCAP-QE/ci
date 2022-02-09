
def TIDB_BRANCH = ghprbTargetBranch

// parse tidb branch
def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIDB_BRANCH = "${m3[0][1]}"
}
m1 = null


def run_with_pod(Closure body) {
    def label = "tidb-test-ghpr-mysql-test-${BUILD_NUMBER}"
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16'
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '200m', resourceRequestMemory: '1Gi',
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
        def tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
        stage("Get commits and Set params") {
            println "TIDB_BRANCH: ${TIDB_BRANCH}"
            println "tidb_sha1: $tidb_sha1"
            ghprbCommentBody = ghprbCommentBody + " /tidb-test=pr/$ghprbPullId"
            println "commentbody: $ghprbCommentBody"
            basic_params = [
                string(name: "upstreamJob", value: "tidb_test_ghpr_mysql_test"),
                string(name: "ghprbCommentBody", value: ghprbCommentBody),
                string(name: "ghprbPullTitle", value: ghprbPullTitle),
                string(name: "ghprbPullLink", value: ghprbPullLink),
                string(name: "ghprbPullDescription", value: ghprbPullDescription),
                string(name: "ghprbTargetBranch", value: TIDB_BRANCH),
                string(name: "ghprbActualCommit", value: tidb_sha1)
            ]

            println "basic_params: $basic_params"
            
        }

        stage("Trigger jobs") {
            build(job: "tidb_ghpr_mysql_test", parameters: basic_params, wait: true)
        }
    }
}