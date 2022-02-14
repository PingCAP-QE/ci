
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
        def ws = pwd()
        def tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${tidb_sha1}/centos7/tidb-server.tar.gz"
        def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${tidb_sha1}/centos7/done"
        
        stage("Get commits and Set params") {
            println "TIDB_BRANCH: ${TIDB_BRANCH}"
            println "tidb_sha1: $tidb_sha1"
            println "tidb_url: $tidb_url"
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

        stage("Checkout") {
            parallel(
                'tidb-test': {
                    dir("go/src/github.com/pingcap/tidb-test") {
                        checkout(changelog: false, poll: false, scm: [
                            $class: "GitSCM",
                            branches: [
                                [name: "${ghprbActualCommit}"],
                            ],
                            userRemoteConfigs: [
                                [
                                    url: "git@github.com:pingcap/tidb-test.git",
                                    refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                    credentialsId: 'github-sre-bot-ssh',
                                ]
                            ],
                            extensions: [
                                [$class: 'PruneStaleBranch'],
                                [$class: 'CleanBeforeCheckout'],
                            ],
                        ])
                    }
                }, 
                'tidb': {
                    dir("go/src/github.com/pingcap/tidb") {
                        deleteDir()
                        timeout(10) {
                            retry(3){
                                deleteDir()
                                sh """
                                while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                                curl ${tidb_url} | tar xz
                                """
                            }
                        }
                    }
                }
            ) 
        }

        stage("Tests") {
            parallel(
                "run-all-tests": {
                    dir("go/src/github.com/pingcap/tidb-test") {
                        timeout(10) {
                            sh """
                            export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                            export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                            cd mysql_test && ./build.sh
                            ./test.sh
                            """
                        }
                    }
                    
                },
                "run-tests-in-wightlist": {
                    build(job: "tidb_ghpr_mysql_test", parameters: basic_params, wait: true)
                }
            )
        }
    }
}

