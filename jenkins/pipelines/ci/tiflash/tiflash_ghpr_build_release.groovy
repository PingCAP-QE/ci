def slackcolor = 'good'
def githash

// # default comment binary download url on pull request
needComment = true

// job param: notcomment default to True
// /release : not comment binary download url
// /release comment=true : comment binary download url
def m1 = ghprbCommentBody =~ /\/run-build-release[\s]comment\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    needComment = "${m1[0][1]}"
    println "needComment=${needComment}"
}
m1 = null

binary = "builds/pingcap/test/tiflash/${ghprbActualCommit}/centos7/tiflash-linux-amd64.tar.gz"
binary_existed = -1

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""

def release_one_amd64(repo,hash) {
    echo "release binary: ${FILE_SERVER_URL}/download/${binary}"
    def paramsBuild = [
            string(name: "ARCH", value: "amd64"),
            string(name: "OS", value: "linux"),
            string(name: "EDITION", value: "community"),
            string(name: "OUTPUT_BINARY", value: binary),
            string(name: "REPO", value: repo),
            string(name: "PRODUCT", value: repo),
            string(name: "GIT_HASH", value: hash),
            string(name: "TARGET_BRANCH", value: ghprbTargetBranch),
            string(name: "GIT_PR", value: ghprbPullId),
            string(name: "BUILD_ENV", value: ''),
            string(name: "BUILDER_IMG", value: ''),
            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
    ]
    echo("default build params: ${paramsBuild}")
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}


def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tiflash"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "hub.pingcap.net/jenkins/centos7_golang-1.18:latest", ttyEnabled: true,
                        resourceRequestCpu: '200m', resourceRequestMemory: '1Gi',
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

try{
    run_with_pod {
        container("golang") {
            stage("Check binary") {
                binary_existed = sh(returnStatus: true,
                        script: """
                if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/${binary}; then exit 0; else exit 1; fi
                """)
                if (binary_existed == 0) {
                    println "tiflash: ${ghprbActualCommit} has beeb build before"
                    println "skip this build"
                } else {
                    println "this commit need build"
                }

            }
            stage("Build") {
                if (binary_existed == 0) {
                    println "skip build..."
                } else {
                    timeout(120) {
                        release_one_amd64("tics", ghprbActualCommit)
                    }
                }
            }
            stage("Print binary url") {
                println "${FILE_SERVER_URL}/download/${binary}"
            }

            stage("Comment on pr") {
                // job param: notcomment default to True
                // /release : not comment binary download url
                // /release comment=true : comment binary download url
                if ( needComment.toBoolean() ) {
                    node("master") {
                        withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                            sh """
                            rm -f comment-pr
                            curl -O http://fileserver.pingcap.net/download/comment-pr
                            chmod +x comment-pr
                            ./comment-pr --token=$TOKEN --owner=pingcap --repo=tics --number=${ghprbPullId} --comment="download tiflash binary(linux amd64) at ${FILE_SERVER_URL}/download/${binary}"
                        """
                        }
                    }
                }
            }
        }
    }
    currentBuild.result = "SUCCESS"
}catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println e
    currentBuild.result = "ABORTED"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
} finally {
    stage("upload-pipeline-data") {
        taskFinishTime = System.currentTimeMillis()
        build job: 'upload-pipelinerun-data',
            wait: false,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_URL', value: "${RUN_DISPLAY_URL}"],
                    [$class: 'StringParameterValue', name: 'REPO', value: "pingcap/tiflash"],
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
}
