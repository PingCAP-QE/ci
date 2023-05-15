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


def TIDB_BRANCH = "master"
def BUILD_NUMBER = "${env.BUILD_NUMBER}"

def PRE_COMMIT = "${ghprbTargetBranch}"
def MYSQL_ARGS = "--log-bin --binlog-format=ROW --enforce-gtid-consistency=ON --gtid-mode=ON --server-id=1 --default-authentication-plugin=mysql_native_password"
def TEST_CASE = ""
def BREAK_COMPATIBILITY = "false"

def specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
if (ghprbPullId == null || ghprbPullId == "") {
    specStr = "+refs/heads/*:refs/remotes/origin/*"
}

println "comment body=${ghprbCommentBody}"

// if this PR breaks compatibility
def m0 = ghprbCommentBody =~ /break_compatibility\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m0) {
    BREAK_COMPATIBILITY = "${m0[0][1]}"
}
m0 = null
println "BREAK_COMPATIBILITY=${BREAK_COMPATIBILITY}"
if (BREAK_COMPATIBILITY == "true") {
    currentBuild.result = 'SUCCESS'
    return 0
}

// parse tidb branch
def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIDB_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

// parser previous commit
def m2 = ghprbCommentBody =~ /pre_commit\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    PRE_COMMIT = "${m2[0][1]}"
}
m2 = null
println "PRE_COMMIT=${PRE_COMMIT}"

// parser test case name
def m3 = ghprbCommentBody =~ /case\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TEST_CASE = "${m3[0][1]}"
}
m3 = null
println "TEST_CASE=${TEST_CASE}"

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
        echo "matched, some diff files full path start with dm/ or pkg/ or go.mod, run the dm compatibility test"
    } else {
        echo "not matched, all files full path not start with dm/ or pkg/ or go.mod, current pr not releate to dm, so skip the dm compatibility test"
        currentBuild.result = 'SUCCESS'
        return 0
    }
}

GO_VERSION = "go1.20"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/wulifu/golang-tini:1.13",
    "go1.16": "hub.pingcap.net/wulifu/golang-tini:1.16",
    "go1.18": "hub.pingcap.net/wulifu/golang-tini:1.18",
    "go1.19": "hub.pingcap.net/wulifu/golang-tini:1.19",
    "go1.20": "hub.pingcap.net/wulifu/golang-tini:1.20",
]
POD_LABEL_MAP = [
    "go1.13": "${JOB_NAME}-go1130-${BUILD_NUMBER}",
    "go1.16": "${JOB_NAME}-go1160-${BUILD_NUMBER}",
    "go1.18": "${JOB_NAME}-go1180-${BUILD_NUMBER}",
    "go1.19": "${JOB_NAME}-go1190-${BUILD_NUMBER}",
    "go1.20": "${JOB_NAME}-go1200-${BUILD_NUMBER}",
]

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""

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


def run_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-dm"
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

catchError {
    stage('Prepare') {
        run_with_pod {
            container("golang") {
                def ws = pwd()
                deleteDir()
                dir("/home/jenkins/agent/git/ticdc") {
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
                    checkout(
                        changelog: false,
                        poll: false,
                        scm: [
                            $class: 'GitSCM',
                            branches: [[name: PRE_COMMIT]],
                            doGenerateSubmoduleConfigurations: false,
                            extensions: [
                                [$class: 'PruneStaleBranch'],
                                [$class: 'CleanBeforeCheckout'],
                            ],
                            submoduleCfg: [],
                            userRemoteConfigs: [[
                                credentialsId: 'github-sre-bot-ssh',
                                refspec: "+refs/heads/*:refs/remotes/origin/* ${specStr}",
                                url: 'git@github.com:pingcap/tiflow.git',
                            ]],
                        ]
                    )
                }

                dir("go/src/github.com/pingcap/tiflow") {
                    sh """
                        cp -R /home/jenkins/agent/git/ticdc/. ./

                        echo "build binary with previous version"
                        git checkout -f ${PRE_COMMIT}
                        export PATH=$PATH:/nfs/cache/gopath/bin:/usr/local/go/bin
                        make dm_integration_test_build
                        mv bin/dm-master.test bin/dm-master.test.previous
                        mv bin/dm-worker.test bin/dm-worker.test.previous

                        echo "build binary with current version"
                        git checkout -f ${ghprbActualCommit}
                        make dm_integration_test_build
                        mv bin/dm-master.test bin/dm-master.test.current
                        mv bin/dm-worker.test bin/dm-worker.test.current
                    """
                }

                stash includes: "go/src/github.com/pingcap/tiflow/**", name: "ticdc", useDefaultExcludes: false

                def tidb_sha1 = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
                sh "curl -C - --retry 3 -f ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"


                // binlogctl
                sh "curl -C - --retry 3 -f http://download.pingcap.org/tidb-enterprise-tools-latest-linux-amd64.tar.gz | tar xz"
                sh "curl -C - --retry 3 -f http://download.pingcap.org/tidb-enterprise-tools-nightly-linux-amd64.tar.gz | tar xz"
                sh "mv tidb-enterprise-tools-nightly-linux-amd64/bin/sync_diff_inspector bin/"
                sh "mv tidb-enterprise-tools-latest-linux-amd64/bin/mydumper bin/"
                sh "rm -r tidb-enterprise-tools-latest-linux-amd64 || true"
                sh "rm -r tidb-enterprise-tools-nightly-linux-amd64 || true"

                // use a new version of gh-ost to overwrite the one in container("golang") (1.0.47 --> 1.1.0)
                sh "curl -C - --retry 3 -fL https://github.com/github/gh-ost/releases/download/v1.1.0/gh-ost-binary-linux-20200828140552.tar.gz | tar xz"
                sh "mv gh-ost bin/"

                // minio
                sh 'curl -C - --retry 3 -fL http://fileserver.pingcap.net/download/minio.tar.gz | tar xz'
                sh 'mv minio bin/'

                stash includes: "bin/**", name: "binaries"
            }
        }
    }

    stage('Compatibility Tests') {
        def label = POD_LABEL_MAP[GO_VERSION]
        podTemplate(label: label,
                cloud: "kubernetes-ng",
                idleMinutes: 0,
                namespace: "jenkins-dm",
                containers: [
                        containerTemplate(name: 'golang',alwaysPullImage: true, image: "${POD_GO_IMAGE}", ttyEnabled: true,
                                resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                                command: 'cat'),
                        containerTemplate(
                                name: 'mysql',
                                image: 'hub.pingcap.net/jenkins/mysql:5.7',
                                ttyEnabled: true,
                                alwaysPullImage: false,
                                resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                                envVars: [
                                        envVar(key: 'MYSQL_ROOT_PASSWORD', value: '123456'),
                                ],
                                args: "${MYSQL_ARGS}",
                        ),
                        // hub.pingcap.net/jenkins/mysql:5.7, registry-mirror.pingcap.net/library/mysql:8.0.21
                        containerTemplate(
                                name: 'mysql1',
                                image: 'hub.pingcap.net/jenkins/mysql:5.7',
                                ttyEnabled: true,
                                alwaysPullImage: true,
                                resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                                envVars: [
                                        envVar(key: 'MYSQL_ROOT_PASSWORD', value: '123456'),
                                        envVar(key: 'MYSQL_TCP_PORT', value: '3307')
                                ],
                                args: "${MYSQL_ARGS}",
                        )
                ], volumes:[
                emptyDirVolume(mountPath: '/tmp', memory: true),
                emptyDirVolume(mountPath: '/home/jenkins', memory: true)
        ]) {
            node(label) {
                container("golang") {
                    timeout(30) {
                        def ws = pwd()
                        deleteDir()
                        unstash "ticdc"
                        unstash "binaries"
                        dir("go/src/github.com/pingcap/tiflow") {
                            try {
                                sh """
                                rm -rf /tmp/dm_test
                                mkdir -p /tmp/dm_test
                                mkdir -p bin
                                mv ${ws}/bin/* bin
                                set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3307 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                export MYSQL_HOST1=127.0.0.1
                                export MYSQL_PORT1=3306
                                export MYSQL_HOST2=127.0.0.1
                                export MYSQL_PORT2=3307
                                export PATH=/usr/local/go/bin:$PATH
                                export GOPATH=\$GOPATH:${ws}/go
                                make dm_compatibility_test CASE=${TEST_CASE}
                                rm -rf cov_dir
                                mkdir -p cov_dir
                                ls /tmp/dm_test
                                cp /tmp/dm_test/cov*out cov_dir
                                """
                            } catch (Exception e) {
                                sh """
                                    for log in `ls /tmp/dm_test/*/*/log/*.log`; do
                                        echo "-----------------\$log begin-----------------"
                                        cat "\$log"
                                        echo "-----------------\$log end-----------------"
                                    done
                                """
                                throw e;
                            } finally {
                                sh """
                                echo success
                                """
                            }
                        }
                        stash includes: "go/src/github.com/pingcap/tiflow/cov_dir/**", name: "integration-cov-${TEST_CASE}"
                    }
                }
            }
        }
    }

    currentBuild.result = "SUCCESS"
}

stage("upload-pipeline-data") {
    taskFinishTime = System.currentTimeMillis()
    build job: 'upload-pipelinerun-data',
        wait: false,
        parameters: [
                [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
                [$class: 'StringParameterValue', name: 'PIPELINE_RUN_URL', value: "${RUN_DISPLAY_URL}"],
                [$class: 'StringParameterValue', name: 'REPO', value: "pingcap/tiflow"],
                [$class: 'StringParameterValue', name: 'COMMIT_ID', value: ghprbActualCommit],
                [$class: 'StringParameterValue', name: 'TARGET_BRANCH', value: ghprbTargetBranch],
                [$class: 'StringParameterValue', name: 'JUNIT_REPORT_URL', value: resultDownloadPath],
                [$class: 'StringParameterValue', name: 'PULL_REQUEST', value: ghprbPullId],
                [$class: 'StringParameterValue', name: 'PULL_REQUEST_AUTHOR', value: params.getOrDefault("ghprbPullAuthorLogin", "default")],
                [$class: 'StringParameterValue', name: 'JOB_TRIGGER', value: params.getOrDefault("ghprbPullAuthorLogin", "default")],
                [$class: 'StringParameterValue', name: 'TRIGGER_COMMENT_BODY', value: params.getOrDefault("ghprbCommentBody", "default")],
                [$class: 'StringParameterValue', name: 'JOB_RESULT_SUMMARY', value: ""],
                [$class: 'StringParameterValue', name: 'JOB_START_TIME', value: "${taskStartTimeInMillis}"],
                [$class: 'StringParameterValue', name: 'JOB_END_TIME', value: "${taskFinishTime}"],
                [$class: 'StringParameterValue', name: 'POD_READY_TIME', value: ""],
                [$class: 'StringParameterValue', name: 'CPU_REQUEST', value: "2000m"],
                [$class: 'StringParameterValue', name: 'MEMORY_REQUEST', value: "8Gi"],
                [$class: 'StringParameterValue', name: 'JOB_STATE', value: currentBuild.result],
                [$class: 'StringParameterValue', name: 'JENKINS_BUILD_NUMBER', value: "${BUILD_NUMBER}"],
    ]
}

stage('Summary') {
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Compatibility Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
