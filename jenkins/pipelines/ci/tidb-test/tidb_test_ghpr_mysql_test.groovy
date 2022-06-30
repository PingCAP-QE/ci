
def TIDB_BRANCH = ghprbTargetBranch

// parse tidb branch
def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIDB_BRANCH = "${m3[0][1]}"
}
m1 = null

GO_VERSION = "go1.18"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
]
POD_LABEL_MAP = [
    "go1.13": "${JOB_NAME}-go1130-${BUILD_NUMBER}",
    "go1.16": "${JOB_NAME}-go1160-${BUILD_NUMBER}",
    "go1.18": "${JOB_NAME}-go1180-${BUILD_NUMBER}",
]

node("master") {
    deleteDir()
    def ws = pwd()
    sh "curl -O https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib.groovy"
    def script_path = "${ws}/goversion-select-lib.groovy"
    def goversion_lib = load script_path
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}


def run_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-tidb-test"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "${POD_GO_IMAGE}", ttyEnabled: true,
                        resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]     
                    )
            ],
            volumes: [
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
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


try {
    run_with_pod {
        container("golang") {
            def ws = pwd()
            def tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
            def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_BRANCH}/${tidb_sha1}/centos7/tidb-server.tar.gz"
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
                        stash includes: "go/src/github.com/pingcap/tidb-test/**", name: "tidb-test"
                    }, 
                    'tidb': {
                        dir("go/src/github.com/pingcap/tidb") {
                            deleteDir()
                            timeout(10) {
                                retry(3){
                                    deleteDir()
                                    sh """
                                    while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 1; done
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
                    "test1": {
                        run_with_pod {
                        container("golang") {
                            dir("go/src/github.com/pingcap/tidb") {
                                timeout(10) {
                                    retry(3){
                                        sh """
                                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 1; done
                                        curl ${tidb_url} | tar xz
                                        """
                                    }
                                }
                            }
                            unstash "tidb-test"
                            dir("go/src/github.com/pingcap/tidb-test") {
                                timeout(10) {
                                    if (ghprbTargetBranch == "master") {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh -backlist=1
                                        pwd && ls -l
                                        """
                                        sh """
                                        pwd && ls -l
                                        cp mysql_test/result.xml test3_result.xml
                                        """
                                        junit testResults: "**/test3_result.xml"
                                    } else {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh
                                        pwd && ls -l
                                        """
                                    }
                                }
                            }
                        }
                        }
                    },
                    "test2": {
                        run_with_pod {
                        container("golang") {
                            dir("go/src/github.com/pingcap/tidb") {
                                timeout(10) {
                                    retry(3){
                                        sh """
                                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 1; done
                                        curl ${tidb_url} | tar xz
                                        """
                                    }
                                }
                            }
                            unstash "tidb-test"
                            dir("go/src/github.com/pingcap/tidb-test") {
                                timeout(10) {
                                    if (ghprbTargetBranch == "master") {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh -backlist=1
                                        pwd && ls -l
                                        """
                                        sh """
                                        pwd && ls -l
                                        cp mysql_test/result.xml test3_result.xml
                                        """
                                        junit testResults: "**/test3_result.xml"
                                    } else {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh
                                        pwd && ls -l
                                        """
                                    }
                                }
                            }
                        }
                        }
                    },
                    "test3": {
                        run_with_pod {
                        container("golang") {
                            dir("go/src/github.com/pingcap/tidb") {
                                timeout(10) {
                                    retry(3){
                                        sh """
                                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 1; done
                                        curl ${tidb_url} | tar xz
                                        """
                                    }
                                }
                            }
                            unstash "tidb-test"
                            dir("go/src/github.com/pingcap/tidb-test") {
                                timeout(10) {
                                    if (ghprbTargetBranch == "master") {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh -backlist=1
                                        pwd && ls -l
                                        """
                                        sh """
                                        pwd && ls -l
                                        cp mysql_test/result.xml test3_result.xml
                                        """
                                        junit testResults: "**/test3_result.xml"
                                    } else {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh
                                        pwd && ls -l
                                        """
                                    }
                                }
                            }
                        }
                        }
                    },
                    "trigger tidb_ghpr_mysql_test": {
                        def tidb_test_download_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/pr/${ghprbActualCommit}/centos7/tidb-test.tar.gz"
                        println "check if current commit is already build, if not wait for build done."
                        timeout(10) {
                            sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_download_url}; do sleep 3; done
                            echo "tidb_test build finished: ${ghprbActualCommit}"
                            """
                        }
                        def built = build(job: "tidb_ghpr_mysql_test", propagate: false, parameters: basic_params, wait: true)
                        println "https://ci.pingcap.net/blue/organizations/jenkins/tidb_ghpr_mysql_test/detail/tidb_ghpr_mysql_test/${built.number}/pipeline"
                        if (built.getResult() != 'SUCCESS') {
                            error "mysql_test failed"
                        }
                    }
                )
            }
        }
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    echo "${e}"
}
