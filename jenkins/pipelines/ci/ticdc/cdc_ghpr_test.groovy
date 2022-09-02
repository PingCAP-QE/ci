echo "Job start..."

def ciRepoUrl = "https://github.com/PingCAP-QE/ci.git"
def ciRepoBranch = "main"

def specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
if (ghprbPullId == null || ghprbPullId == "") {
    specStr = "+refs/heads/*:refs/remotes/origin/*"
}

GO_VERSION = "go1.19"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18.5:latest",
    "go1.19": "hub.pingcap.net/jenkins/centos7_golang-1.19:latest",
]
POD_LABEL_MAP = [
    "go1.13": "${JOB_NAME}-go1130-${BUILD_NUMBER}",
    "go1.16": "${JOB_NAME}-go1160-${BUILD_NUMBER}",
    "go1.18": "${JOB_NAME}-go1180-${BUILD_NUMBER}",
    "go1.19": "${JOB_NAME}-go1190-${BUILD_NUMBER}",
]
def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""

node("master") {
    deleteDir()
    def goversion_lib_url = 'https://raw.githubusercontent.com/purelind/ci-1/purelind/tidb-it-use-go1.19/jenkins/pipelines/ci/tidb/goversion-select-lib.groovy'
    sh "curl -O --retry 3 --retry-delay 5 --retry-connrefused --fail ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}


def run_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-ticdc"
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

catchError {
    run_with_pod() {
        stage('Prepare') {
            container("golang") {
                def ws = pwd()
                deleteDir()

                dir("${ws}/go/src/github.com/pingcap/tiflow") {
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
                    sh "git checkout -f ${ghprbActualCommit}"
                }

                dir("${ws}/go/src/github.com/pingcap/ci") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/ci"
                        deleteDir()
                    }
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: "${ciRepoBranch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[refspec: specStr, url: "${ciRepoUrl}"]]]
                    } catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/ci"
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: "${ciRepoBranch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[refspec: specStr, url: "${ciRepoUrl}"]]]
                        }
                    }

                }

                stash includes: "go/src/github.com/pingcap/tiflow/**", name: "ticdc", useDefaultExcludes: false
            }
        }

        stage("Unit Test") {
            container("golang") {
                def ws = pwd()
                deleteDir()
                unstash 'ticdc'

                dir("go/src/github.com/pingcap/tiflow") {
                    sh """
                        rm -rf /tmp/tidb_cdc_test
                        mkdir -p /tmp/tidb_cdc_test
                        GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make test
                        rm -rf cov_dir
                        mkdir -p cov_dir
                        ls /tmp/tidb_cdc_test
                        cp /tmp/tidb_cdc_test/cov*out cov_dir
                    """
                    sh """
                        tail /tmp/tidb_cdc_test/cov*
                    """
                    archiveArtifacts artifacts: 'cov_dir/*', fingerprint: true
                    withCredentials([string(credentialsId: 'codecov-token-ticdc', variable: 'CODECOV_TOKEN')]) {
                        timeout(30) {
                            sh '''
                            rm -rf /tmp/tidb_cdc_test
                            mkdir -p /tmp/tidb_cdc_test
                            cp cov_dir/* /tmp/tidb_cdc_test
                            set +x
                            BUILD_NUMBER=${BUILD_NUMBER} CODECOV_TOKEN="${CODECOV_TOKEN}" COVERALLS_TOKEN="${COVERALLS_TOKEN}" GOPATH=${ws}/go:\$GOPATH PATH=${ws}/go/bin:/go/bin:\$PATH JenkinsCI=1 make unit_test_coverage
                            set -x
                            '''
                        }
                    }
                }
            }
            currentBuild.result = "SUCCESS"
        }
    }
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
                [$class: 'StringParameterValue', name: 'CPU_REQUEST', value: "4000m"],
                [$class: 'StringParameterValue', name: 'MEMORY_REQUEST', value: "8Gi"],
                [$class: 'StringParameterValue', name: 'JOB_STATE', value: currentBuild.result],
                [$class: 'StringParameterValue', name: 'JENKINS_BUILD_NUMBER', value: "${BUILD_NUMBER}"],
    ]
}




