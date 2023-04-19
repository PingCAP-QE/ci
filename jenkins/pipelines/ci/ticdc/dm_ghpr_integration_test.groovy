/*
    Run dm unit/intergation test in Jenkins with String paramaters
    * ghprbActualCommit (by bot)
    * ghprbPullId (by bot)
    * COVERALLS_TOKEN (set default in jenkins admin)
    * CODECOV_TOKEN (set default in jenkins admin)
*/


echo "release test: ${params.containsKey("release_test")}"

if (params.containsKey("release_test")) {
    ghprbActualCommit = params.release_test__dm_commit
    ghprbTargetBranch = params.release_test__release_branch
    ghprbPullId = ""
    ghprbCommentBody = ""
    ghprbPullLink = "release-test"
    ghprbPullTitle = "release-test"
    ghprbPullDescription = "release-test"
}

specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
if (ghprbPullId == null || ghprbPullId == "") {
    specStr = "+refs/heads/*:refs/remotes/origin/*"
}

// prepare all vars
MYSQL_ARGS = '--ssl=ON --log-bin --binlog-format=ROW --enforce-gtid-consistency=ON --gtid-mode=ON --server-id=1 --default-authentication-plugin=mysql_native_password'
MYSQL_HOST = '127.0.0.1'
MYSQL_PORT = 3306
MYSQL2_PORT = 3307
MYSQL_PSWD = 123456

def print_all_vars() {
    println '================= ALL TEST VARS ================='
    println "[MYSQL_HOST]: ${MYSQL_HOST}"
    println "[MYSQL_PORT]: ${MYSQL_PORT}"
    println "[MYSQL2_PORT]: ${MYSQL2_PORT}"
    println "[MYSQL_PSWD]: ${MYSQL_PSWD}"
    println "[MYSQL_ARGS]: ${MYSQL_ARGS}"
}

def list_pr_diff_files() {
    def list_pr_files_api_url = "https://api.github.com/repos/${ghprbGhRepository}/pulls/${ghprbPullId}/files"
    withCredentials([string(credentialsId: 'github-api-token-test-ci', variable: 'github_token')]) {
        response = httpRequest consoleLogResponseBody: false,
            contentType: 'APPLICATION_JSON', httpMode: 'GET',
            customHeaders:[[name:'Authorization', value:"token ${github_token}", maskValue: true]],
            url: list_pr_files_api_url, validResponseCodes: '200'

        def json = new groovy.json.JsonSlurper().parseText(response.content)

        echo "Status: ${response.status}"
        def files = []
        for (element in json) {
            files.add(element.filename)
        }

        println "pr diff files: ${files}"
        return files
    }
}

// if any file matches the pattern, return true
def pattern_match_any_file(pattern, files_list) {
    for (file in files_list) {
        if (file.matches(pattern)) {
            println "diff file matched: ${file}"
            return true
        }
    }

    return false
}

if (ghprbPullId != null && ghprbPullId != "" && !params.containsKey("triggered_by_upstream_pr_ci")) {
    def pr_diff_files = list_pr_diff_files()
    def pattern = /(^dm\/|^pkg\/|^go\.mod).*$/
    // if any diff files start with dm/ or pkg/ , run the dm integration test
    def matched = pattern_match_any_file(pattern, pr_diff_files)
    if (matched) {
        echo "matched, some diff files full path start with dm/ or pkg/ or go.mod, run the dm integration test"
    } else {
        echo "not matched, all files full path not start with dm/ or pkg/ or go.mod, current pr not releate to dm, so skip the dm integration test"
        currentBuild.result = 'SUCCESS'
        return 0
    }
}

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
POD_NAMESPACE = "jenkins-dm"

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

def run_test_with_pod(Closure body) {
    def label = "dm-integration-test-${BUILD_NUMBER}"
    if (GO_VERSION == "go1.13") {
        label = "dm-integration-test-go1130-${BUILD_NUMBER}"
    }
    if (GO_VERSION == "go1.16") {
        label = "dm-integration-test-go1160-${BUILD_NUMBER}"
    }
    if (GO_VERSION == "go1.18") {
        label = "dm-integration-test-go1180-${BUILD_NUMBER}"
    }
    if (GO_VERSION == "go1.19") {
        label = "dm-integration-test-go1190-${BUILD_NUMBER}"
    }
    def cloud = "kubernetes-ng"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(
            label: label,
            cloud: cloud,
            idleMinutes: 0,
            namespace: "jenkins-dm",
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: true,
                            image: "${POD_GO_IMAGE}", ttyEnabled: true,
                            resourceRequestCpu: '3000m', resourceRequestMemory: '4Gi',
                            resourceLimitCpu: '12000m', resourceLimitMemory: "12Gi",
                            command: 'cat'),
                    containerTemplate(
                            name: 'mysql1', alwaysPullImage: true,
                            image: 'hub.pingcap.net/jenkins/mysql:5.7',ttyEnabled: true,
                            resourceRequestCpu: '500m', resourceRequestMemory: '1Gi',
                            envVars: [
                                    envVar(key: 'MYSQL_ROOT_PASSWORD', value: "${MYSQL_PSWD}"),
                            ],
                            args: "${MYSQL_ARGS}"),
                    // mysql 8
                    containerTemplate(
                            name: 'mysql2', alwaysPullImage: false,
                            image: 'registry-mirror.pingcap.net/library/mysql:8.0.21',ttyEnabled: true,
                            resourceRequestCpu: '500m', resourceRequestMemory: '1Gi',
                            envVars: [
                                    envVar(key: 'MYSQL_ROOT_PASSWORD', value: "${MYSQL_PSWD}"),
                                    envVar(key: 'MYSQL_TCP_PORT', value: "${MYSQL2_PORT}")
                            ],
                            args: "${MYSQL_ARGS}")
            ]
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            body()
        }
    }
}

def run_build_with_pod(Closure body) {
    def label = "dm-integration-test-build-${BUILD_NUMBER}"
    if (GO_VERSION == "go1.13") {
        label = "dm-integration-test-build-go1130-${BUILD_NUMBER}"
    }
    if (GO_VERSION == "go1.16") {
        label = "dm-integration-test-build-go1160-${BUILD_NUMBER}"
    }
    if (GO_VERSION == "go1.18") {
        label = "dm-integration-test-build-go1180-${BUILD_NUMBER}"
    }
    if (GO_VERSION == "go1.19") {
        label = "dm-integration-test-build-go1190-${BUILD_NUMBER}"
    }
    def cloud = "kubernetes-ng"
    podTemplate(label: label,
            cloud: cloud,
            idleMinutes: 0,
            namespace: "jenkins-dm",
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
                            emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            body()
        }
    }
}


def checkout_and_stash_dm_code() {
    run_build_with_pod {
        container('golang') {
            deleteDir()

            dir('/home/jenkins/agent/git/ticdc') {
                def codeCacheInFileserverUrl = "${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tiflow.tar.gz"
                def cacheExisted = sh(returnStatus: true, script: """
                    if curl --output /dev/null --silent --head --fail ${codeCacheInFileserverUrl}; then exit 0; else exit 1; fi
                    """)
                if (cacheExisted == 0) {
                    println "get code from fileserver to reduce clone time"
                    println "codeCacheInFileserverUrl=${codeCacheInFileserverUrl}"
                    sh """
                    curl -C - --retry 3 -fO ${codeCacheInFileserverUrl}
                    tar -xzf src-tiflow.tar.gz --strip-components=1
                    rm -f src-tiflow.tar.gz
                    """
                } else {
                    println "get code from github"
                }
                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tiflow.git']]]
            }

            dir('go/src/github.com/pingcap/tiflow') {
                sh """cp -R /home/jenkins/agent/git/ticdc/. ./
                    git checkout -f ${ghprbActualCommit}
                    """
            }
            stash includes: 'go/src/github.com/pingcap/tiflow/**', name: 'ticdc', useDefaultExcludes: false
        }
    }
}

dm_feature_branch = ["release-multi-source", "refactor-syncer"]

def build_dm_bin() {
    run_build_with_pod {
        container('golang') {
            deleteDir()
            unstash 'ticdc'
            ws = pwd()
            dir('go/src/github.com/pingcap/tiflow') {
                println "debug command:\nkubectl -n jenkins-tidb exec -ti ${env.NODE_NAME} bash"

                // build it test bin
                sh 'make dm_integration_test_build'

                // tidb
                def TIDB_BRANCH = ghprbTargetBranch
                def PD_BRACNCH = ghprbTargetBranch
                def TIKV_BRANCH = ghprbTargetBranch
                if (!TIDB_BRANCH.startsWith("release-") || TIDB_BRANCH in dm_feature_branch) {
                    TIDB_BRANCH = "master"
                }
                def pattern = /^(release-\d.\d)-(.*)/
                def m1 = TIDB_BRANCH =~ pattern
                if (m1) {
                    print "this is a hotfix branch: ${TIDB_BRANCH}"
                    TIDB_BRANCH_REMOVE_DATE_SUFFIX = "${m1[0][1]}"
                    TIDB_BRANCH = TIDB_BRANCH_REMOVE_DATE_SUFFIX
                    PD_BRACNCH = TIDB_BRANCH_REMOVE_DATE_SUFFIX
                    TIKV_BRANCH = TIDB_BRANCH_REMOVE_DATE_SUFFIX
                    println "current branch is a hotfix branch, so we will use the branch name without date suffix: ${TIDB_BRANCH}"
                }
                m1 = null
                TIDB_BRANCH = params.getOrDefault("release_test__tidb_commit", TIDB_BRANCH)
                PD_BRACNCH = params.getOrDefault("release_test__pd_commit", PD_BRACNCH)
                TIKV_BRANCH = params.getOrDefault("release_test__tikv_commit", TIKV_BRANCH)
                println "TIDB_BRANCH=${TIDB_BRANCH}"
                println "PD_BRACNCH=${PD_BRACNCH}"
                println "TIKV_BRANCH=${TIKV_BRANCH}"


                tidb_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
                sh "curl -C - --retry 3 -f -o tidb-server.tar.gz ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_BRANCH}/${tidb_sha1}/centos7/tidb-server.tar.gz"
                sh 'mkdir -p tidb-server'
                sh 'tar -zxf tidb-server.tar.gz -C tidb-server'
                sh 'mv tidb-server/bin/tidb-server bin/'
                sh 'rm -r tidb-server'
                sh 'rm -r tidb-server.tar.gz'

                pd_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRACNCH}/sha1").trim()
                sh "curl -C - --retry 3 -f -o pd-server.tar.gz ${FILE_SERVER_URL}/download/builds/pingcap/pd/${PD_BRACNCH}/${pd_sha1}/centos7/pd-server.tar.gz"
                sh 'mkdir -p pd-server'
                sh 'tar -zxf pd-server.tar.gz -C pd-server'
                sh 'mv pd-server/bin/pd-server bin/'
                sh 'rm -r pd-server'
                sh 'rm -r pd-server.tar.gz'

                tikv_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1").trim()
                sh "curl -C - --retry 3 -f -o tikv-server.tar.gz ${FILE_SERVER_URL}/download/builds/pingcap/tikv/${TIKV_BRANCH}/${tikv_sha1}/centos7/tikv-server.tar.gz"
                sh 'mkdir -p tikv-server'
                sh 'tar -zxf tikv-server.tar.gz -C tikv-server'
                sh 'mv tikv-server/bin/tikv-server bin/'
                sh 'rm -r tikv-server'
                sh 'rm -r tikv-server.tar.gz'

                tools_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/tidb-tools/master/sha1").trim()
                sh "curl -C - --retry 3 -f -o tidb-tools.tar.gz ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/${tools_sha1}/centos7/tidb-tools.tar.gz"
                sh 'mkdir -p tidb-tools'
                sh 'tar -zxf tidb-tools.tar.gz -C tidb-tools'
                sh 'mv tidb-tools/bin/sync_diff_inspector bin/'
                sh 'rm -r tidb-tools'
                sh 'rm -r tidb-tools.tar.gz'

                // use a new version of gh-ost to overwrite the one in container("golang") (1.0.47 --> 1.1.0)
                sh 'curl -C - --retry 3 -f -L https://github.com/github/gh-ost/releases/download/v1.1.0/gh-ost-binary-linux-20200828140552.tar.gz | tar xz'
                sh 'mv gh-ost bin/'

                // minio
                sh 'curl -C - --retry 3 -f -L http://fileserver.pingcap.net/download/minio.tar.gz | tar xz'
                sh 'mv minio bin/'
            }
            dir("${ws}") {
                stash includes: 'go/src/github.com/pingcap/tiflow/**', name: 'ticdc-with-bin', useDefaultExcludes: false
            }
        }
    }
}


def run_tls_source_it_test(String case_name) {
    run_test_with_pod {
        // stash  ssl certs to jenkins, i don't know why if filename == client-key.pem , the stash will fail
        // so just hack the filename
        container('mysql1') {
            def ws = pwd()
            deleteDir()
            sh "set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done"
            sh "cp -r /var/lib/mysql/*.pem ."
            sh "ls"
            sh "pwd"
            sh "cat client-key.pem > client.key.pem"
            sh "cat client.key.pem"
            stash includes: 'ca.pem,client-cert.pem,client.key.pem', name: "mysql-certs", useDefaultExcludes: false
        }

        container('golang') {
            def ws = pwd()
            deleteDir()
            unstash(name: 'mysql-certs')
            sh "ls"
            sh "mv client.key.pem client-key.pem"
            sh "sudo mkdir -p /var/lib/mysql"
            sh "sudo chmod 777 /var/lib/mysql"
            sh "cp *.pem /var/lib/mysql/"
            sh "ls /var/lib/mysql"

            unstash 'ticdc-with-bin'
            dir('go/src/github.com/pingcap/tiflow') {
                try {
                    sh"""
                            rm -rf /tmp/dm_test
                            mkdir -p /tmp/dm_test
                            export MYSQL_HOST1=${MYSQL_HOST}
                            export MYSQL_PORT1=${MYSQL_PORT}
                            export MYSQL_HOST2=${MYSQL_HOST}
                            export MYSQL_PORT2=${MYSQL2_PORT}
                            # wait for mysql container ready.
                            set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                            set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3307 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                            # run test
                            export PATH=/usr/local/go/bin:$PATH
                            export GOPATH=\$GOPATH:${ws}/go
                            make dm_integration_test CASE="${case_name}"
                            # upload coverage
                            rm -rf cov_dir
                            mkdir -p cov_dir
                            ls /tmp/dm_test
                            cp /tmp/dm_test/cov*out cov_dir || true
                            """
                }catch (Exception e) {
                    sh """
                                echo "${case_name} test faild print all log..."
                                for log in `ls /tmp/dm_test/*/*/log/*.log`; do
                                    echo "____________________________________"
                                    echo "\$log"
                                    cat "\$log"
                                    echo "____________________________________"
                                done
                                """
                    throw e
                }
            }
            try {
                stash includes: 'go/src/github.com/pingcap/tiflow/cov_dir/**', name: "integration-cov-${case_name}"
            } catch (Exception e) {
                println e
            }
        }
    }
}


def run_single_it_test(String case_name) {
    run_test_with_pod {
        container('golang') {
            def ws = pwd()
            deleteDir()
            unstash 'ticdc-with-bin'
            dir('go/src/github.com/pingcap/tiflow') {
                try {
                    sh"""
                            rm -rf /tmp/dm_test
                            mkdir -p /tmp/dm_test
                            export MYSQL_HOST1=${MYSQL_HOST}
                            export MYSQL_PORT1=${MYSQL_PORT}
                            export MYSQL_HOST2=${MYSQL_HOST}
                            export MYSQL_PORT2=${MYSQL2_PORT}
                            # wait for mysql container ready.
                            set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                            set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3307 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                            # run test
                            export PATH=/usr/local/go/bin:$PATH
                            export GOPATH=\$GOPATH:${ws}/go
                            make dm_integration_test CASE="${case_name}"
                            # upload coverage
                            rm -rf cov_dir
                            mkdir -p cov_dir
                            ls /tmp/dm_test
                            cp /tmp/dm_test/cov*out cov_dir || true
                            """
                }catch (Exception e) {
                    sh """
                                echo "${case_name} test faild print all log..."
                                for log in `ls /tmp/dm_test/*/*/log/*.log`; do
                                    echo "____________________________________"
                                    echo "\$log"
                                    cat "\$log"
                                    echo "____________________________________"
                                done
                                """
                    throw e
                }
            }
            try {
                stash includes: 'go/src/github.com/pingcap/tiflow/cov_dir/**', name: "integration-cov-${case_name}"
            } catch (Exception e) {
                println e
            }
        }
    }
}

def run_make_coverage() {
    run_build_with_pod {
        ws = pwd()
        deleteDir()
        try {
            unstash 'integration-cov-all_mode'
            unstash 'integration-cov-dmctl_advance dmctl_basic dmctl_command'
            unstash 'integration-cov-ha_cases'
            unstash 'integration-cov-ha_cases_1'
            unstash 'integration-cov-ha_cases_2'
            unstash 'integration-cov-ha_cases2'
            unstash 'integration-cov-ha_cases3'
            unstash 'integration-cov-ha_cases3_1'
            unstash 'integration-cov-ha_master'
            unstash 'integration-cov-handle_error'
            unstash 'integration-cov-handle_error_2'
            unstash 'integration-cov-handle_error_3'
            unstash 'integration-cov-import_goroutine_leak incremental_mode initial_unit'
            unstash 'integration-cov-load_interrupt'
            unstash 'integration-cov-many_tables'
            unstash 'integration-cov-online_ddl'
            unstash 'integration-cov-relay_interrupt'
            unstash 'integration-cov-safe_mode sequence_safe_mode'
            unstash 'integration-cov-shardddl1'
            unstash 'integration-cov-shardddl1_1'
            unstash 'integration-cov-shardddl2'
            unstash 'integration-cov-shardddl2_1'
            unstash 'integration-cov-shardddl3'
            unstash 'integration-cov-shardddl3_1'
            unstash 'integration-cov-shardddl4'
            unstash 'integration-cov-shardddl4_1'
            unstash 'integration-cov-sharding sequence_sharding'
            unstash 'integration-cov-start_task'
            unstash 'integration-cov-print_status http_apis'
            unstash 'integration-cov-new_relay'
            unstash 'integration-cov-import_v10x'
            unstash 'integration-cov-tls'
            unstash 'integration-cov-sharding2'
            unstash 'integration-cov-ha'
            unstash 'integration-cov-others'
            unstash 'integration-cov-others_2'
            unstash 'integration-cov-others_3'
        } catch (Exception e) {
            println e
        }
        dir('go/src/github.com/pingcap/tiflow') {
            container('golang') {
                withCredentials([
                    string(credentialsId: 'coveralls-token-tiflow', variable: 'COVERALLS_TOKEN'),
                    string(credentialsId: 'codecov-token-ticdc', variable: 'CODECOV_TOKEN')
                ]) {
                    timeout(30) {
                        sh """
                        rm -rf /tmp/dm_test
                        mkdir -p /tmp/dm_test
                        cp cov_dir/* /tmp/dm_test
                        set +x
                        BUILD_NUMBER=${BUILD_NUMBER} COVERALLS_TOKEN="${COVERALLS_TOKEN}" CODECOV_TOKEN="${CODECOV_TOKEN}" PATH=${ws}/go/bin:/go/bin:\$PATH JenkinsCI=1 make dm_coverage || true
                        set -x
                        """
                    }
                }
            }
        }
    }
}

pipeline {
    agent any

    stages {
        stage('Check Code') {
            steps {
                print_all_vars()
                script {
                    try {
                        checkout_and_stash_dm_code()
                    }catch (info) {
                        retry(count: 3) {
                            echo 'checkout failed, retry..'
                            sleep 1
                            checkout_and_stash_dm_code()
                        }
                    }
                }
            }
        }

        stage('Build Bin') {
            options { retry(count: 3) }
            steps {
                build_dm_bin()
            }
        }

        stage('Parallel Run Tests') {
            failFast true
            parallel {
                stage('IT-all_mode') {
                    steps {
                        script {
                            run_single_it_test('all_mode')
                        }
                    }
                }

                stage('IT-dmctl') {
                    steps {
                        script {
                            run_single_it_test('dmctl_advance dmctl_basic dmctl_command')
                        }
                    }
                }

                stage('IT-ha_cases') {
                    steps {
                        script {
                            run_single_it_test('ha_cases')
                        }
                    }
                }

                stage('IT-ha_cases_1') {
                    steps {
                        script {
                            run_single_it_test('ha_cases_1')
                        }
                    }
                }

                stage('IT-ha_cases_2') {
                    steps {
                        script {
                            run_single_it_test('ha_cases_2')
                        }
                    }
                }

                stage('IT-ha_cases2') {
                    steps {
                        script {
                            run_single_it_test('ha_cases2')
                        }
                    }
                }

                stage('IT-ha_cases3') {
                    steps {
                        script {
                            run_single_it_test('ha_cases3')
                        }
                    }
                }

                stage('IT-ha_cases3_1') {
                    steps {
                        script {
                            run_single_it_test('ha_cases3_1')
                        }
                    }
                }

                stage('IT-ha_master') {
                    steps {
                        script {
                            run_single_it_test('ha_master')
                        }
                    }
                }

                stage('IT-handle_error') {
                    steps {
                        script {
                            run_single_it_test('handle_error')
                        }
                    }
                }

                stage('IT-handle_error_2') {
                    steps {
                        script {
                            run_single_it_test('handle_error_2')
                        }
                    }
                }

                stage('IT-handle_error_3') {
                    steps {
                        script {
                            run_single_it_test('handle_error_3')
                        }
                    }
                }

                stage('IT-i* group') {
                    steps {
                        script {
                            run_single_it_test('import_goroutine_leak incremental_mode initial_unit')
                        }
                    }
                }

                stage('IT-load_interrupt') {
                    steps {
                        script {
                            run_single_it_test('load_interrupt')
                        }
                    }
                }

                stage('IT-many_tables') {
                    steps {
                        script {
                            run_single_it_test('many_tables')
                        }
                    }
                }

                stage('IT-online_ddl') {
                    steps {
                        script {
                            run_single_it_test('online_ddl')
                        }
                    }
                }

                stage('IT-relay_interrupt') {
                    steps {
                        script {
                            run_single_it_test('relay_interrupt')
                        }
                    }
                }

                stage('IT-safe_mode group') {
                    steps {
                        script {
                            run_single_it_test('safe_mode sequence_safe_mode')
                        }
                    }
                }

                stage('IT-shardddl1') {
                    steps {
                        script {
                            run_single_it_test('shardddl1')
                        }
                    }
                }

                stage('IT-shardddl1_1') {
                    steps {
                        script {
                            run_single_it_test('shardddl1_1')
                        }
                    }
                }

                stage('IT-shardddl2') {
                    steps {
                        script {
                            run_single_it_test('shardddl2')
                        }
                    }
                }

                stage('IT-shardddl2_1') {
                    steps {
                        script {
                            run_single_it_test('shardddl2_1')
                        }
                    }
                }

                stage('IT-shardddl3') {
                    steps {
                        script {
                            run_single_it_test('shardddl3')
                        }
                    }
                }

                stage('IT-shardddl3_1') {
                    steps {
                        script {
                            run_single_it_test('shardddl3_1')
                        }
                    }
                }

                stage('IT-shardddl4') {
                    steps {
                        script {
                            run_single_it_test('shardddl4')
                        }
                    }
                }

                stage('IT-shardddl4_1') {
                    steps {
                        script {
                            run_single_it_test('shardddl4_1')
                        }
                    }
                }

                stage('IT-sharding group') {
                    steps {
                        script {
                            run_single_it_test('sharding sequence_sharding')
                        }
                    }
                }

                stage('IT-start_task') {
                    steps {
                        script {
                            run_single_it_test('start_task')
                        }
                    }
                }

                stage('IT-status_and_apis') {
                    steps {
                        script {
                            run_single_it_test('print_status http_apis')
                        }
                    }
                }

                stage('IT-new_relay') {
                    steps {
                        script {
                            run_single_it_test('new_relay')
                        }
                    }
                }

                stage('IT-import_v10x') {
                    steps {
                        script {
                            run_single_it_test('import_v10x')
                        }
                    }
                }

                stage('IT-tls') {
                    steps {
                        script {
                            run_tls_source_it_test('tls')
                        }
                    }
                }

                stage('IT-sharding2') {
                    steps {
                        script {
                            run_single_it_test('sharding2')
                        }
                    }
                }

                stage('IT-ha') {
                    steps {
                        script {
                            run_single_it_test('ha')
                        }
                    }
                }

                stage('IT-others') {
                    steps {
                        script {
                            run_single_it_test('others')
                        }
                    }
                }

                stage('IT-others-2') {
                    steps {
                        script {
                            run_single_it_test('others_2')
                        }
                    }
                }

                stage('IT-others-3') {
                    steps {
                        script {
                            run_single_it_test('others_3')
                        }
                    }
                }
            }
        }

        stage('Coverage') {
            steps {
                run_make_coverage()
            }
        }

        stage('Print Summary') {
            steps {
                script {
                    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
                    println "all test succeed time=${duration}"
                }
            }
        }
    }
}
