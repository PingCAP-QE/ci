def release_docker_image(product, filepath, tag) {
    def image = "pingcap/${product}:$tag"
    echo "docker image ${image}"

    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/${product}"
    def paramsDocker = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: filepath),
        string(name: "REPO", value: product),
        string(name: "PRODUCT", value: product),
        string(name: "RELEASE_TAG", value: tag),
        string(name: "DOCKERFILE", value: dockerfile),
        string(name: "RELEASE_DOCKER_IMAGES", value: image),
    ]
    build job: "docker-common",
            wait: true,
            parameters: paramsDocker
}


// choose which go version to use.
def String selectGoVersion(String branchORTag) {
    return "go1.18"
}


def GO_BUILD_SLAVE = GO1180_BUILD_SLAVE
def goVersion = selectGoVersion(env.BRANCH_NAME)
println "This build use ${goVersion}"

def BUILD_URL = 'git@github.com:pingcap/tiflow.git'

def build_path = 'go/src/github.com/pingcap/tiflow'
def slackcolor = 'good'
def githash
def ws
def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"

def os = "linux"
def arch = "amd64"

def isHotfix = false
if ( env.BRANCH_NAME.startsWith("v") &&  env.BRANCH_NAME =~ ".*-202.*") {
    isHotfix = true
}

def release_one(repo,product,hash) {
    def binary = "builds/pingcap/test/${env.BRANCH_NAME}/${repo}/${hash}/centos7/${product}-linux-arm64.tar.gz"
    echo "release binary: ${FILE_SERVER_URL}/download/${binary}"
    def paramsBuild = [
        string(name: "ARCH", value: "arm64"),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: "dataflow-engine"),
        string(name: "GIT_HASH", value: hash),
        string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
    ]
    if (env.TAG_NAME != null) {
        paramsBuild.push(string(name: "RELEASE_TAG", value: env.TAG_NAME))
    }
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}

try {
    node("${GO_BUILD_SLAVE}") {

        stage("Debug Info"){
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            ws = pwd()
            deleteDir()
        }

        stage("Checkout") {
            dir(build_path) {
                // 如果不是 TAG，直接传 branch 给下面的 checkout 语句； 否则就应该 checkout 到 refs/tags 下 .
                // 值得注意的是，即使传入的是 TAG，环境变量里的 BRANCH_NAME 和 TAG_NAME 同时会是 TAG 名，如 v3.0.0
                println branch
                retry(3) {
                    if(branch.startsWith("refs/tags")) {
                        checkout changelog: false,
                                poll: true,
                                scm: [$class: 'GitSCM',
                                        branches: [[name: branch]],
                                        doGenerateSubmoduleConfigurations: false,
                                        extensions: [[$class: 'CheckoutOption', timeout: 30],
                                                    [$class: 'LocalBranch'],
                                                    [$class: 'CloneOption', noTags: true, timeout: 60]],
                                        submoduleCfg: [],
                                        userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                            refspec: "+${branch}:${branch}",
                                                            url: "${BUILD_URL}"]]
                                ]
                    } else {
                        checkout scm: [$class: 'GitSCM',
                            branches: [[name: branch]],
                            extensions: [[$class: 'LocalBranch']],
                            userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}"]]]
                    }
                }


                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }
        }

        stage("Build") {
            dir(build_path) {
                container("golang") {
                    timeout(20) {
                        sh """
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        GOPATH=\$GOPATH:${ws}/go make engine
                        """
                    }
                }
            }
        }

        stage("Upload") {
            dir(build_path) {
                def target = "dataflow-engine-${os}-${arch}"
                def refspath = "refs/pingcap/tiflow/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/tiflow/${env.BRANCH_NAME}/${githash}/centos7/${target}.tar.gz"
                def filepath2 = "builds/pingcap/tiflow/${githash}/centos7/${target}.tar.gz"
                def patch_path = "builds/pingcap/tiflow/patch/${env.BRANCH_NAME}/${githash}/centos7/${target}.tar.gz"
                container("golang") {
                    timeout(10) {
                        sh """
                        echo "${githash}" > sha1
                        curl --fail -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                        mkdir -p ${target}/bin
                        tar -czvf ${target}.tar.gz bin
                        curl --fail -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                        curl --fail -F ${filepath2}=@${target}.tar.gz ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                        """
                    }
                    release_one("tiflow","dataflow-engine","${githash}")
                }
            }
        }
    }

    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}

stage('Summary') {
    echo "Send slack here ..."
    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}\n @here"
    if (currentBuild.result != "SUCCESS" && (branch == "master" || branch.startsWith("release") || branch.startsWith("refs/tags/v"))) {
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
