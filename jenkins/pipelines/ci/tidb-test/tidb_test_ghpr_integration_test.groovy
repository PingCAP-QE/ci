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

node("master") {
    deleteDir()
    def goversion_lib_url = 'https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib-upgrade-temporary.groovy'
    sh "curl --retry 3 --retry-delay 5 --retry-connrefused --fail -o goversion-select-lib.groovy  ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}
POD_NAMESPACE = "jenkins-tidb-test"


def run_test_with_java_pod(Closure body) {
    def label = "tidb-ghpr-integration-test-java-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    podTemplate(label: label,
            cloud: cloud,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'java', alwaysPullImage: false,
                            image: "hub.pingcap.net/jenkins/centos7_golang-1.16_openjdk-17.0.2_gradle-7.4.2_maven-3.8.6:cached", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            body()
        }
    }
}

def run_test_with_ruby_pod(Closure body) {
    def label = "tidb-ghpr-integration-test-ruby-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    podTemplate(label: label,
            cloud: cloud,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'ruby', alwaysPullImage: false,
                            image: "hub-new.pingcap.net/jenkins/ruby27:latest", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            body()
        }
    }
}

def run_test_with_pod(Closure body) {
    def label = ""
    if (GO_VERSION == "go1.13") {
        label = "${JOB_NAME}-go1130-${BUILD_NUMBER}"
    }
    if (GO_VERSION == "go1.16") {
        label = "${JOB_NAME}-go1160-${BUILD_NUMBER}"
    }
    if (GO_VERSION == "go1.18") {
        label = "${JOB_NAME}-go1180-${BUILD_NUMBER}"
    }
    if (GO_VERSION == "go1.19") {
        label = "${JOB_NAME}-go1190-${BUILD_NUMBER}"
    }
    def cloud = "kubernetes-ksyun"
    podTemplate(label: label,
            cloud: cloud,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${POD_GO_IMAGE}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: true)
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            body()
        }
    }
}


def run_with_toolkit_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tidb-test"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'toolkit', alwaysPullImage: true,
                            image: "hub.pingcap.net/qa/ci-toolkit:latest", ttyEnabled: true,
                            resourceRequestCpu: '1000m', resourceRequestMemory: '1Gi',
                            command: '/bin/sh -c', args: 'cat'
                    )
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

run_with_toolkit_pod {
    container('toolkit') {
        def basic_params = []
        def tidb_params = []
        def tikv_params = []
        def pd_params = []

        def tidb_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
        def tikv_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1").trim()
        def pd_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1").trim()
        stage("Get commits and Set params") {

            println "TIDB_BRANCH=${TIDB_BRANCH}\nTIKV_BRANCH=${TIKV_BRANCH}\nPD_BRANCH=${PD_BRANCH}"
            println "tidb_sha1: $tidb_sha1\ntikv_sha1: $tikv_sha1\npd_sha1: $pd_sha1"


            ghprbCommentBody = ghprbCommentBody + " /tidb-test=pr/$ghprbPullId"
            println "commentbody: $ghprbCommentBody"

            basic_params = [
                    string(name: "upstreamJob", value: "tidb_test_ghpr_integration_test"),
                    string(name: "ghprbCommentBody", value: ghprbCommentBody),
                    string(name: "ghprbPullTitle", value: ghprbPullTitle),
                    string(name: "ghprbPullLink", value: ghprbPullLink),
                    string(name: "ghprbPullDescription", value: ghprbPullDescription),
                    booleanParam(name: 'force', value: true),
            ]

            tidb_params = basic_params + [
                    string(name: "ghprbTargetBranch", value: TIDB_BRANCH),
                    string(name: "ghprbActualCommit", value: tidb_sha1)
            ]

            tikv_params = basic_params + [
                    string(name: "ghprbTargetBranch", value: TIKV_BRANCH),
                    string(name: "ghprbActualCommit", value: tikv_sha1)
            ]

            pd_params = basic_params + [
                    string(name: "ghprbTargetBranch", value: PD_BRANCH),
                    string(name: "ghprbActualCommit", value: pd_sha1),
            ]
        }

        stage("copy files"){
            // 由于 ghpr 产生的包来自 pr ，储存路径为 builds/pingcap/tidb/pr/COMMIT ，而这里我们的 commit 是从 branch 上取的，tar 包的位置在 builds/pingcap/tidb/COMMIT
            // 下游集成测试会从 pr/COMMIT 路径下载包，就会导致 not found
            // 这里 参照 qa_release_test 做个 hack,拷贝相关包到对应路径,  tikv 同理
            sh """
            rm -f tidb-server.tar.gz
            inv upload --dst builds/pingcap/tidb/pr/${tidb_sha1}/centos7/done --content done

            inv upload --dst builds/download/refs/pingcap/tikv/${tikv_sha1}/sha1 --content $tikv_sha1
            inv upload --dst builds/download/refs/pingcap/pd/$pd_sha1/sha1 --content $pd_sha1
            """
        }

        stage("Checkout") {
            parallel(
                    'tidb-test': {
                        dir("go/src/github.com/pingcap/tidb-test") {
                            def codeCacheInFileserverUrl = "${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb-test.tar.gz"
                            def cacheExisted = sh(returnStatus: true, script: """
                                if curl --output /dev/null --silent --head --fail ${codeCacheInFileserverUrl}; then exit 0; else exit 1; fi
                                """)
                            if (cacheExisted == 0) {
                                println "get code from fileserver to reduce clone time"
                                println "codeCacheInFileserverUrl=${codeCacheInFileserverUrl}"
                                sh """
                                curl -C - --retry 3 -f -O ${codeCacheInFileserverUrl}
                                tar -xzf src-tidb-test.tar.gz --strip-components=1
                                rm -f src-tidb-test.tar.gz
                                """
                            } else {
                                println "get code from github"
                            }
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
                                    def tidb_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${tidb_sha1}/centos7/tidb-server.tar.gz"
                                    deleteDir()
                                    sh """
                                while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 1; done
                                curl -C - --retry 3 -f ${tidb_url} | tar xz
                                """
                                }
                            }
                        }
                    }
            )
        }


        stage("Tests") {
            def tests = [:]

            def run = { test_dir, mytest, test_cmd ->
                run_test_with_pod {
                    def ws = pwd()
                    deleteDir()
                    unstash "tidb-test"
                    dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                        container("golang") {
                            timeout(20) {
                                retry(3){
                                    sh """
                                    tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
                                    pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                                    tidb_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${tidb_sha1}/centos7/tidb-server.tar.gz"

                                    while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 1; done
                                    curl -C - --retry 3 -f \${tikv_url} | tar xz bin

                                    while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 1; done
                                    curl -C - --retry 3 -f \${pd_url} | tar xz bin

                                    mkdir -p ./tidb-src
                                    curl -C - --retry 3 -f \${tidb_url} | tar xz -C ./tidb-src
                                    ln -s \$(pwd)/tidb-src "${ws}/go/src/github.com/pingcap/tidb"
                                    mv tidb-src/bin/tidb-server ./bin/tidb-server
                                    ./bin/tidb-server -V
                                    """
                                }
                            }
                            try {
                                timeout(20) {
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

                                    pwd && ls -alh
                                    export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
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

            def run_java = { test_dir, mytest, test_cmd ->
                run_test_with_java_pod {
                    def ws = pwd()
                    deleteDir()
                    unstash "tidb-test"
                    dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                        container("java") {
                            timeout(20) {
                                retry(3){
                                    sh """
                                    tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
                                    pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                                    tidb_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${tidb_sha1}/centos7/tidb-server.tar.gz"

                                    while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 1; done
                                    curl -C - --retry 3 -f \${tikv_url} | tar xz bin

                                    while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 1; done
                                    curl -C - --retry 3 -f \${pd_url} | tar xz bin

                                    mkdir -p ./tidb-src
                                    curl -C - --retry 3 -f \${tidb_url} | tar xz -C ./tidb-src
                                    ln -s \$(pwd)/tidb-src "${ws}/go/src/github.com/pingcap/tidb"
                                    mv tidb-src/bin/tidb-server ./bin/tidb-server
                                    """
                                }
                            }
                            try {
                                timeout(20) {
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

                                    export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
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

            def run_ruby = { test_dir, mytest, test_cmd ->
                run_test_with_ruby_pod {
                    def ws = pwd()
                    deleteDir()
                    unstash "tidb-test"
                    dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                        container("ruby") {
                            timeout(20) {
                                retry(3){
                                    sh """
                                    tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
                                    pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                                    tidb_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${tidb_sha1}/centos7/tidb-server.tar.gz"

                                    while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 1; done
                                    curl -C - --retry 3 -f \${tikv_url} | tar xz bin

                                    while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 1; done
                                    curl -C - --retry 3 -f \${pd_url} | tar xz bin

                                    mkdir -p ./tidb-src
                                    curl -C - --retry 3 -f \${tidb_url} | tar xz -C ./tidb-src
                                    ln -s \$(pwd)/tidb-src "${ws}/go/src/github.com/pingcap/tidb"
                                    mv tidb-src/bin/tidb-server ./bin/tidb-server
                                    """
                                }
                            }
                            try {
                                timeout(20) {
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

                                    export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
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

            tests["Integration Analyze Test"] = {
                run("analyze_test", "analyzetest", "./test.sh")
            }
            tests["Integration Rangen Test"] = {
                run("randgen-test", "rangentest", "./test.sh")
            }
            tests["Go SQL Test"] = {
                run("go-sql-test", "gosqltest", "./test.sh")
            }
            tests["GORM Test"] = {
                run("gorm_test", "gormtest", "./test.sh")
            }
            tests["Beego ORM Test"] = {
                run("beego_orm_test", "beegoormtest", "./test.sh")
            }
            tests["Upper DB ORM Test"] = {
                run("upper_db_orm_test", "upperdbormtest", "./test.sh")
            }
            tests["XORM Test"] = {
                run("xorm_test", "xormtest", "./test.sh")
            }

            tests["JDBC8 Fast Test"] = {
                run_java("jdbc8_test", "jdbc8test", "./test_fast.sh")
            }

            tests["JDBC8 Slow Test"] = {
                run_java("jdbc8_test", "jdbc8test", "./test_slow.sh")
            }

            tests["Hibernate Test"] = {
                run_java("hibernate_test/hibernate-orm-test", "hibernatetest", "./test.sh")
            }

            tests["MyBatis Test"] = {
                run_java("mybatis_test", "mybatistest", "./test.sh")
            }

            tests["jOOQ Test"] = {
                run_java("jooq_test", "jooqtest", "./test.sh")
            }

            tests["TiDB JDBC8 Fast Test"] = {
                run_java("tidb_jdbc_test/tidb_jdbc8_test", "tidbjdbc8test", "./test_fast.sh")
            }

            tests["TiDB JDBC8 Slow Test"] = {
                run_java("tidb_jdbc_test/tidb_jdbc8_test", "tidbjdbc8test", "./test_slow.sh")
            }

            tests["TiDB JDBC Unique Test"] = {
                run_java("tidb_jdbc_test/tidb_jdbc_unique_test", "tidbuniquetest", "./test.sh")
            }

            tests["TiDB JDBC without TLS Test"] = {
                run_java("tidb_jdbc_test/tidb_jdbc8_tls_test", "tidbtlstest", "./test_slow.sh")
            }

            tests["TiDB JDBC TLS Test"] = {
                run_java("tidb_jdbc_test/tidb_jdbc8_tls_test", "tidbtlstest", "./test_tls.sh")
            }

            tests["mysql_connector_c Test"] = {
                run("mysql_connector_c_test", "mysql_connector_c_test", "./test.sh")
            }

            tests["ActiveRecord Test"] = {
                run_ruby("activerecord_orm_test/activerecord-orm-test/activerecord", "activerecord_test", "./test.sh")
            }

            println tidb_params
            def tidb_test_download_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/pr/${ghprbActualCommit}/centos7/tidb-test.tar.gz"
            println "check if current commit is already build, if not wait for build done."
            timeout(10) {
                sh """
                while ! curl --output /dev/null --silent --head --fail ${tidb_test_download_url}; do sleep 3; done
                echo "tidb_test build finished: ${ghprbActualCommit}"
                """
            }

            tests["trigger common_test"] = {
                def built1 = build(job: "tidb_ghpr_integration_common_test", wait: true, propagate: false, parameters: tidb_params)
                println "https://ci.pingcap.net/blue/organizations/jenkins/tidb_test_ghpr_integration_test/detail/tidb_ghpr_integration_common_test/${built1.number}/pipeline"
                if (built1.getResult() != 'SUCCESS') {
                    error "common_test failed"
                }
            }
            tests["trigger ddl_test"] =  {
                def built2 = build(job: "tidb_ghpr_integration_ddl_test", wait: true, propagate: false, parameters: tidb_params)
                println "https://ci.pingcap.net/blue/organizations/jenkins/tidb_test_ghpr_integration_test/detail/tidb_ghpr_integration_ddl_test/${built2.number}/pipeline"
                if (built2.getResult() != 'SUCCESS') {
                    error "ddl_test failed"
                }
            }
            tests["trigger compatibility_test"]= {
                def built3 = build(job: "tidb_ghpr_integration_campatibility_test", wait: true, propagate: false, parameters: tidb_params)
                println "https://ci.pingcap.net/blue/organizations/jenkins/tidb_test_ghpr_integration_test/detail/tidb_ghpr_integration_campatibility_test/${built3.number}/pipeline"
                if (built3.getResult() != 'SUCCESS') {
                    error "compatibility_test failed"
                }
            }

            parallel tests
        }
    }
}
