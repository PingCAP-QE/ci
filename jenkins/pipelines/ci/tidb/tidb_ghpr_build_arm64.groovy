echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tidb_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

// # default comment binary download url on pull request
needComment = true

// job param: notcomment default to True
// /release : not comment binary download url
// /release comment=true : comment binary download url
def m1 = ghprbCommentBody =~ /\/run-build-arm64[\s]comment\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    needComment = "${m1[0][1]}"
    println "needComment=${needComment}"
}
m1 = null


binary = "builds/pingcap/test/tidb/${ghprbActualCommit}/centos7/tidb-linux-arm64.tar.gz"
binary_existed = -1


def release_one_arm64(repo,hash) {
    echo "release binary: ${FILE_SERVER_URL}/download/${binary}"
    def paramsBuild = [
            string(name: "ARCH", value: "arm64"),
            string(name: "OS", value: "linux"),
            string(name: "EDITION", value: "community"),
            string(name: "OUTPUT_BINARY", value: binary),
            string(name: "REPO", value: repo),
            string(name: "PRODUCT", value: repo),
            string(name: "GIT_HASH", value: hash),
            string(name: "TARGET_BRANCH", value: ghprbTargetBranch),
            string(name: "GIT_PR", value: ghprbPullId),
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
    def namespace = "jenkins-tidb"
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
                    println "tidb: ${ghprbActualCommit} has beeb build before"
                    println "skip this build"
                } else {
                    println "this commit need build"
                }

            }

            stage("Build") {
                if (binary_existed == 0) {
                    println "skip build..."
                } else {
                    release_one_arm64("tidb", ghprbActualCommit)
                }
            }

            stage("Print binary url") {
                println "${FILE_SERVER_URL}/download/${binary}"
            }

            stage("Comment on pr") {
                // job param: notcomment default to True
                // /release : not comment binary download url
                // /release comment=true : comment binary download url
                if (!notcomment.toBoolean()) {
                    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                        sh """
                            rm -f comment-pr
                            curl -O http://fileserver.pingcap.net/download/comment-pr
                            chmod +x comment-pr
                            ./comment-pr --token=$TOKEN --owner=pingcap --repo=tidb --number=${ghprbPullId} --comment="download tidb binary(linux arm64) at ${FILE_SERVER_URL}/download/${binary}"
                        """
                    }
                }
            }
        }
    }
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}
