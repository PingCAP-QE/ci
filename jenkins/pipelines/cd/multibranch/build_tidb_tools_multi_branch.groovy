// choose which go version to use. 
def selectGoVersion(branchNameOrTag) {
    if (branchNameOrTag.startsWith("v")) {
        println "This is a tag"
        if (branchNameOrTag >= "v6.3") {
            println "tag ${branchNameOrTag} use go 1.19"
            return "go1.19"
        }
        if (branchNameOrTag >= "v6.0") {
            println "tag ${branchNameOrTag} use go 1.18"
            return "go1.18"
        }
        if (branchNameOrTag >= "v5.1") {
            println "tag ${branchNameOrTag} use go 1.16"
            return "go1.16"
        }
        if (branchNameOrTag < "v5.1") {
            println "tag ${branchNameOrTag} use go 1.13"
            return "go1.13"
        }
        println "tag ${branchNameOrTag} use default version go 1.19"
        return "go1.19"
    } else { 
        println "this is a branch"
        if (branchNameOrTag == "master") {
            println("branchNameOrTag: master  use go1.19")
            return "go1.19"
        }


        if (branchNameOrTag.startsWith("release-") && branchNameOrTag >= "release-6.3") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.18")
            return "go1.18"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-6.3"  && branchNameOrTag >= "release-6.0") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.18")
            return "go1.18"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-6.0" && branchNameOrTag >= "release-5.1") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.16")
            return "go1.16"
        }

        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-5.1") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.13")
            return "go1.13"
        }
        println "branchNameOrTag: ${branchNameOrTag}  use default version go1.18"
        return "go1.19"
    }
}


def GO_BUILD_SLAVE = "build_go1190"
def goVersion = selectGoVersion(env.BRANCH_NAME)
if ( goVersion == "go1.18" ) {
    GO_BUILD_SLAVE = "build_go1180"
}
if ( goVersion == "go1.16" ) {
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
}
if ( goVersion == "go1.13" ) {
    GO_BUILD_SLAVE = "build_go1130_memvolume"
}

println "This build use ${goVersion}"

def BUILD_URL = 'git@github.com:pingcap/tidb-tools.git'

def build_path = 'go/src/github.com/pingcap/tidb-tools'
def slackcolor = 'good'
def githash
def ws
def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"

def os = "linux"
def arch = "amd64"

def release_one(repo,hash) {
    def binary = "builds/pingcap/test/${repo}/${hash}/centos7/${repo}-linux-arm64.tar.gz"
    echo "release binary: ${FILE_SERVER_URL}/download/${binary}"
    def paramsBuild = [
        string(name: "ARCH", value: "arm64"),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: repo),
        string(name: "GIT_HASH", value: hash),
        string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
    ]
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
                        GOPATH=${ws}/go make build
                        """
                    }
                }
            }
        }

        stage("Upload") {
            dir(build_path) {
                def refspath = "refs/pingcap/tidb-tools/${env.BRANCH_NAME}/sha1"
                def nightlyrefpath = "refs/pingcap/tidb-tools/nightly/sha1"
                def filepath = "builds/pingcap/tidb-tools/${githash}/centos7/tidb-tools.tar.gz"
                container("golang") {
                    release_one("tidb-tools","${githash}")
                    timeout(10) {
                        sh """
                        echo "${githash}" > sha1
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        tar czvf tidb-tools.tar.gz bin/*
                        curl -F ${filepath}=@tidb-tools.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
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