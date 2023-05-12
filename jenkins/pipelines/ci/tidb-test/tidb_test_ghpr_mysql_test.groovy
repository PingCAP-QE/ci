


def (TIKV_BRANCH, PD_BRANCH, TIDB_BRANCH) = [ghprbTargetBranch, ghprbTargetBranch, ghprbTargetBranch]

// parse tikv branch
def m1 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIKV_BRANCH = "${m1[0][1]}"
}
m1 = null

// parse pd branch
def m2 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    PD_BRANCH = "${m2[0][1]}"
}
m2 = null

// parse tidb branch
def m3 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_BRANCH = "${m3[0][1]}"
}
m3 = null


GO_VERSION = "go1.20"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
    "go1.19": "hub.pingcap.net/jenkins/centos7_golang-1.19:latest",
    "go1.20": "hub.pingcap.net/jenkins/centos7_golang-1.20:latest",
]
POD_LABEL_MAP = [
    "go1.13": "${JOB_NAME}-go1130-${BUILD_NUMBER}",
    "go1.16": "${JOB_NAME}-go1160-${BUILD_NUMBER}",
    "go1.18": "${JOB_NAME}-go1180-${BUILD_NUMBER}",
    "go1.19": "${JOB_NAME}-go1190-${BUILD_NUMBER}",
    "go1.20": "${JOB_NAME}-go1200-${BUILD_NUMBER}",
]

node("master") {
    deleteDir()
    def goversion_lib_url = 'https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/ci/tidb/goversion-select-lib.groovy'
    sh "curl --retry 3 --retry-delay 5 --retry-connrefused --fail -o goversion-select-lib.groovy  ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}


def run_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kuberenetes-ksyun"
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
            // def tidb_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
            // def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_BRANCH}/${tidb_sha1}/centos7/tidb-server.tar.gz"
            def tidb_url = ""

            def tikv_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1").trim()
            def pd_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1").trim()
            def tikv_url= "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
            def pd_url= "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

            stage("Get commits and Set params") {
                println "TIDB_BRANCH: ${TIDB_BRANCH}"
                // println "tidb_sha1: $tidb_sha1"
                // println "tidb_url: $tidb_url"
                // ghprbCommentBody = ghprbCommentBody + " /tidb-test=pr/$ghprbPullId"
                // println "commentbody: $ghprbCommentBody"
                // basic_params = [
                //     string(name: "upstreamJob", value: "tidb_test_ghpr_mysql_test"),
                //     string(name: "ghprbCommentBody", value: ghprbCommentBody),
                //     string(name: "ghprbPullTitle", value: ghprbPullTitle),
                //     string(name: "ghprbPullLink", value: ghprbPullLink),
                //     string(name: "ghprbPullDescription", value: ghprbPullDescription),
                //     string(name: "ghprbTargetBranch", value: TIDB_BRANCH),
                //     string(name: "ghprbActualCommit", value: tidb_sha1)
                // ]

                // println "basic_params: $basic_params"
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
                                    def codeCacheInFileserverUrl = "${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb.tar.gz"
                                    def cacheExisted = sh(returnStatus: true, script: """
                                        if curl --output /dev/null --silent --head --fail ${codeCacheInFileserverUrl}; then exit 0; else exit 1; fi
                                        """)
                                    if (cacheExisted == 0) {
                                        println "get code from fileserver to reduce clone time"
                                        println "codeCacheInFileserverUrl=${codeCacheInFileserverUrl}"
                                        sh """
                                        curl -C - --retry 3 -f -O ${codeCacheInFileserverUrl}
                                        tar -xzf src-tidb.tar.gz --strip-components=1
                                        rm -f src-tidb.tar.gz
                                        """
                                    } else {
                                        println "get code from github"
                                    }
                                    checkout(changelog: false, poll: false, scm: [
                                        $class                           : "GitSCM",
                                        branches                         : [
                                                [name: TIDB_BRANCH],
                                        ],
                                        userRemoteConfigs                : [
                                                [
                                                        url          : "git@github.com:pingcap/tidb.git",
                                                        refspec      : "+refs/heads/*:refs/remotes/origin/*",
                                                        credentialsId: "github-sre-bot-ssh",
                                                ]
                                        ],
                                        extensions                       : [
                                                [$class             : 'SubmoduleOption',
                                                disableSubmodules  : false,
                                                parentCredentials  : true,
                                                recursiveSubmodules: true,
                                                trackingSubmodules : false,
                                                reference          : ''],
                                                [$class: 'PruneStaleBranch'],
                                                [$class: 'CleanBeforeCheckout'],
                                        ],
                                        doGenerateSubmoduleConfigurations: false,
                                    ])

                                    def tidb_commit_hash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                                    def tidb_build_ci_cache_filepath = "ci-cache-build/tidb-test-ci/tidb/${tidb_commit_hash}/centos7/tidb-server-linux-amd64.tar.gz"
                                    tidb_url = "${FILE_SERVER_URL}/download/${tidb_build_ci_cache_filepath}"
                                    sh """
                                    make server
                                    rm -rf .git
                                    tar czvf tidb-server-linux-amd64.tar.gz ./*
                                    curl -f -F ${tidb_build_ci_cache_filepath}=@tidb-server-linux-amd64.tar.gz ${FILE_SERVER_URL}/upload
                                    """
                                }
                            }
                        }
                    }
                )
            }

            stage("Tests") {
                def run = { test_dir, mytest, test_cmd ->
                    run_with_pod {
                        def cur_ws = pwd()
                        unstash "tidb-test"
                        dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                            container("golang") {
                                timeout(20) {
                                    retry(3){
                                    sh """
                                    while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 1; done
                                    curl -C - --retry 3 -f ${tikv_url} | tar xz bin

                                    while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 1; done
                                    curl -C - --retry 3 -f ${pd_url} | tar xz bin

                                    mkdir -p ./tidb-src
                                    curl -C - --retry 3 -f ${tidb_url} | tar xz -C ./tidb-src
                                    ln -s \$(pwd)/tidb-src "${cur_ws}/go/src/github.com/pingcap/tidb"
                                    mv tidb-src/bin/tidb-server ./bin/tidb-server
                                    ./bin/tidb-server -V
                                    """
                                    }
                                }
                                try {
                                    timeout(40) {
                                        sh """
                                        ps aux
                                        set +e
                                        killall -9 -r tidb-server
                                        killall -9 -r tikv-server
                                        killall -9 -r pd-server
                                        rm -rf /tmp/tidb
                                        rm -rf ./tikv ./pd
                                        set -e

                                        bin/pd-server --name=pd --data-dir=pd &>pd_${mytest}.log &
                                        sleep 10
                                        echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                        bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                                        sleep 10
                                        if [ -f test.sh ]; then awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh; fi

                                        export TIDB_SRC_PATH=${cur_ws}/go/src/github.com/pingcap/tidb
                                        export log_level=debug
                                        TIDB_SERVER_PATH=`pwd`/bin/tidb-server \
                                        TIKV_PATH='127.0.0.1:2379' \
                                        TIDB_TEST_STORE_NAME=tikv \
                                        ${test_cmd}
                                    """
                                    }
                                } catch (err) {
                                    sh"""
                                    cat mysql-test.out || true
                                    """
                                    sh """
                                    cat pd_${mytest}.log
                                    cat tikv_${mytest}.log
                                    cat tidb*.log
                                    """
                                    throw err
                                } finally {
                                    sh """
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    set -e
                                    """
                                }
                            }
                        }
                    }
                }

                parallel(
                    "test1": {
                        run_with_pod {
                        container("golang") {
                            dir("go/src/github.com/pingcap/tidb") {
                                timeout(10) {
                                    retry(3){
                                        sh """
                                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 1; done
                                        curl -C - --retry 3 -f ${tidb_url} | tar xz
                                        """
                                    }
                                }
                            }
                            unstash "tidb-test"
                            dir("go/src/github.com/pingcap/tidb-test") {
                                timeout(20) {
                                    if (ghprbTargetBranch == "master") {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh -backlist=1 -part=1
                                        pwd && ls -l
                                        """
                                        sh """
                                        pwd && ls -l
                                        cp mysql_test/result.xml test1_result.xml
                                        """
                                        junit testResults: "**/test1_result.xml"
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
                                        curl -C - --retry 3 -f ${tidb_url} | tar xz
                                        """
                                    }
                                }
                            }
                            unstash "tidb-test"
                            dir("go/src/github.com/pingcap/tidb-test") {
                                timeout(20) {
                                    if (ghprbTargetBranch == "master") {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh -backlist=1 -part=2
                                        pwd && ls -l
                                        """
                                        sh """
                                        pwd && ls -l
                                        cp mysql_test/result.xml test2_result.xml
                                        """
                                        junit testResults: "**/test2_result.xml"
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
                                        curl -C - --retry 3 -f ${tidb_url} | tar xz
                                        """
                                    }
                                }
                            }
                            unstash "tidb-test"
                            dir("go/src/github.com/pingcap/tidb-test") {
                                timeout(20) {
                                    if (ghprbTargetBranch == "master") {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh -backlist=1 -part=3
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
                    "test4": {
                        run_with_pod {
                        container("golang") {
                            dir("go/src/github.com/pingcap/tidb") {
                                timeout(10) {
                                    retry(3){
                                        sh """
                                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 1; done
                                        curl -C - --retry 3 -f ${tidb_url} | tar xz
                                        """
                                    }
                                }
                            }
                            unstash "tidb-test"
                            dir("go/src/github.com/pingcap/tidb-test") {
                                timeout(20) {
                                    if (ghprbTargetBranch == "master") {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh -backlist=1 -part=4
                                        pwd && ls -l
                                        """
                                        sh """
                                        pwd && ls -l
                                        cp mysql_test/result.xml test4_result.xml
                                        """
                                        junit testResults: "**/test4_result.xml"
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
//                     "integration-mysql-test-Cached": {
//                         if (ghprbTargetBranch == "master") {
//                             run("mysql_test", "mysqltest", "CACHE_ENABLED=1 ./test.sh -backlist=1  ")
//                         } else {
//                             println "skip"
//                         }
//                     },
//                     "integration-mysql-test": {
//                         if (ghprbTargetBranch == "master") {
//                             run("mysql_test", "mysqltest", "./test.sh -backlist=1  ")
//                         } else {
//                             println "skip"
//                         }
//                     }
                )
            }
        }
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    echo "${e}"
}
