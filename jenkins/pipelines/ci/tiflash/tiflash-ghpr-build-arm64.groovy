properties([
        parameters([
            booleanParam(
                        name: 'needComment',
                        defaultValue: true
            ),
            string(
                        defaultValue: '',
                        name: 'ghprbActualCommit',
                        trim: true,
            ),
            string(
                        defaultValue: '',
                        name: 'ghprbPullId',
                        trim: true,
            ),
            string(
                        defaultValue: '',
                        name: 'ghprbTargetBranch',
                        trim: true,
            ),
                        string(
                        defaultValue: '',
                        name: 'ghprbPullTitle',
                        trim: true,
            ),
            string(
                        defaultValue: '',
                        name: 'ghprbPullLink',
                        trim: true,
            ),
            string(
                        defaultValue: '',
                        name: 'ghprbPullDescription',
                        trim: true,
            ),
            string(
                        defaultValue: '',
                        name: 'ghprbCommentBody',
                        trim: true,
            ),
    ]),
])


// # default comment binary download url on pull request
needComment = true

// job param: notcomment default to True
// /run-build-arm64 : comment binary download url
// /run-build-arm64 comment=false: not comment binary download url
def m1 = ghprbCommentBody =~ /\/run-build-arm64[\s]comment\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    needComment = "${m1[0][1]}"
    println "needComment=${needComment}"
}
m1 = null

binary = "builds/pingcap/test/tiflash/${ghprbTargetBranch}/${ghprbActualCommit}/centos7/tiflash-linux-arm64.tar.gz"
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
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-tiflash"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
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
            stage("Check binary exists") {
                binary_existed = sh(returnStatus: true,
                        script: """
                if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/${binary}; then exit 0; else exit 1; fi
                """)
                if (binary_existed == 0) {
                    println "tiflash: ${ghprbActualCommit} on branch ${ghprbTargetBranch} has been build before"
                    println "skip this build"
                } else {
                    println "this commit need build"
                }
            }

            stage("Build") {
                if (binary_existed == 0) {
                    println "skip build..."
                } else {
                    release_one_arm64("tics", ghprbActualCommit)
                }
            }

            stage("Print binary url") {
                println "${FILE_SERVER_URL}/download/${binary}"
            }

            stage("Comment on pr") {
                if ( needComment.toBoolean() ) {
                    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                        sh """
                        rm -f comment-pr
                        curl -O http://fileserver.pingcap.net/download/comment-pr
                        chmod +x comment-pr
                        ./comment-pr --token=$TOKEN --owner=pingcap --repo=tiflash --number=${ghprbPullId} --comment="download tiflash binary(linux arm64) at ${FILE_SERVER_URL}/download/${binary}"
                    """
                    }
                }
            }
        }
    }

} catch (Exception e) {
    currentBuild.result = "FAILURE"
    echo "${e}"
}
