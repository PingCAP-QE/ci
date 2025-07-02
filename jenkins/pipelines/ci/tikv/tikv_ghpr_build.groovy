echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    echo "release test: ${params.containsKey("release_test")}"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tikv_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def slackcolor = 'good'
def githash

def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
def release="dist_release"


def ghprbCommentBody = params.ghprbCommentBody ?: null

// job param: notcomment default to True
// /release : not comment binary download url
// /release comment=true : comment binary download url
def m2 = ghprbCommentBody =~ /\/run-build[\s]comment\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    needComment = "${m2[0][1]}"
    if ( needComment == "true" || needComment == "True" ) {
        notcomment = false
    }
}
m2 = null

def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}
println "specStr=${specStr}"

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""

def run_build_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tikv"
    def rust_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_rust:latest"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            workspaceVolume: emptyDirWorkspaceVolume(memory: true),
            containers: [
                    containerTemplate(
                        name: 'rust', alwaysPullImage: true,
                        image: rust_image, ttyEnabled: true,
                        resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]
                    )
            ],
            volumes: [
                    // TODO use s3 cache instead of nfs
                    nfsVolume(mountPath: '/rust/registry/cache', serverAddress: "${NFS_SERVER_ADDRESS}",
                            serverPath: '/data/nvme1n1/nfs/tikv/rust/registry/cache', readOnly: false),
                    nfsVolume(mountPath: '/rust/registry/index', serverAddress: "${NFS_SERVER_ADDRESS}",
                            serverPath: '/data/nvme1n1/nfs/tikv/rust/registry/index', readOnly: false),
                    nfsVolume(mountPath: '/rust/git/db', serverAddress: "${NFS_SERVER_ADDRESS}",
                            serverPath: '/data/nvme1n1/nfs/tikv/rust/git/db', readOnly: false),
                    nfsVolume(mountPath: '/rust/git/checkouts', serverAddress: "${NFS_SERVER_ADDRESS}",
                            serverPath: '/data/nvme1n1/nfs/tikv/rust/git/checkouts', readOnly: false),
                    emptyDirVolume(mountPath: '/tmp', memory: true),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: true),
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            println "rust image: ${rust_image}"
            body()
        }
    }
}

try {
    run_build_with_pod {
        def ws = pwd()
        deleteDir()

        stage("Checkout") {
            container("rust") {
                // update cache
                dir("tikv") {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:tikv/tikv.git']]]
                    sh """
                        rm ~/.gitconfig || true
                        git checkout -f ${ghprbActualCommit}
                    """
                }
            }
        }

        stage("Build") {
            container("rust") {
                dir("tikv") {
                    timeout(120) {
                    sh """
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                        CARGO_TARGET_DIR=/home/jenkins/agent/.target ROCKSDB_SYS_STATIC=1 make ${release}
                        # use make release
                        mkdir -p bin
                        cp /home/jenkins/agent/.target/release/tikv-server bin/
                        cp /home/jenkins/agent/.target/release/tikv-ctl bin
                    """
                    }
                }
            }
        }

        stage("Upload") {
            def filepath = "builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
            def donepath = "builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/done"
            def refspath = "refs/pingcap/tikv/pr/${ghprbPullId}/sha1"
            if (params.containsKey("triggered_by_upstream_ci")) {
                refspath = "refs/pingcap/tikv/pr/branch-${ghprbTargetBranch}/sha1"
            }

            dir("tikv") {
                container("rust") {
                    timeout(10) {
                        sh """
                        tar czvf tikv-server.tar.gz bin/*
                        curl -F ${filepath}=@tikv-server.tar.gz ${FILE_SERVER_URL}/upload
                        echo "pr/${ghprbActualCommit}" > sha1
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        echo "done" > done
                        curl -F ${donepath}=@done ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
    }

    // job param: notcomment default to True
    // /release : not comment binary download url
    // /release comment=true : comment binary download url
    if (!notcomment.toBoolean()) {
        node("master") {
            withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                sh """
                    rm -f comment-pr
                    curl -O http://fileserver.pingcap.net/download/comment-pr
                    chmod +x comment-pr
                    ./comment-pr --token=$TOKEN --owner=tikv --repo=tikv --number=${ghprbPullId} --comment="download tikv at ${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
                """
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
    if (e.getMessage().equals("hasBeenTested")) {
        currentBuild.result = "SUCCESS"
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
} finally {
    stage("upload-pipeline-data") {
        taskFinishTime = System.currentTimeMillis()
        build job: 'upload-pipelinerun-data',
            wait: false,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_URL', value: "${RUN_DISPLAY_URL}"],
                    [$class: 'StringParameterValue', name: 'REPO', value: "tikv/tikv"],
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
