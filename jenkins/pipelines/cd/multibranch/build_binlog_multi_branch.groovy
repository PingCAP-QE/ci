// choose which go version to use. 
def String selectGoVersion(String branchORTag) {
    def goVersion="go1.18"
    if (branchORTag.startsWith("v") && branchORTag <= "v5.1") {
        return "go1.13"
    }
    if (branchORTag.startsWith("v") && branchORTag > "v5.1" && branchORTag < "v6.0") {
        return "go1.16"
    }
    if (branchORTag.startsWith("release-") && branchORTag < "release-5.1"){
        return "go1.13"
    }
    if (branchORTag.startsWith("release-") && branchORTag >= "release-5.1" && branchORTag < "release-6.0"){
        return "go1.16"
    }
    if (branchORTag.startsWith("hz-poc") || branchORTag.startsWith("arm-dup") ) {
        return "go1.16"
    }
    return "go1.18"
}


def GO_BUILD_SLAVE = GO1180_BUILD_SLAVE
def goVersion = selectGoVersion(env.BRANCH_NAME)
if ( goVersion == "go1.16" ) {
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
}
if ( goVersion == "go1.13" ) {
    GO_BUILD_SLAVE = GO_BUILD_SLAVE
}

println "This build use ${goVersion}"



def BUILD_URL = 'git@github.com:pingcap/tidb-binlog.git'

def build_path = 'go/src/github.com/pingcap/tidb-binlog'
def slackcolor = 'good'
def githash
def ws
def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"

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
                        GOPATH=${ws}/go make
                        """
                    }
                }
            }
        }

        stage("Upload") {
            dir(build_path) {
                def refspath = "refs/pingcap/tidb-binlog/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/tidb-binlog/${githash}/centos7/tidb-binlog.tar.gz"
                def filepath2 = "builds/pingcap/tidb-binlog/${env.BRANCH_NAME}/${githash}/centos7/tidb-binlog.tar.gz"
                container("golang") {
                    //release_one("tidb-binlog","${githash}")
                    timeout(10) {
                        sh """
                        echo "${githash}" > sha1
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        tar czvf tidb-binlog.tar.gz bin/*
                        curl -F ${filepath}=@tidb-binlog.tar.gz ${FILE_SERVER_URL}/upload
                        curl -F ${filepath2}=@tidb-binlog.tar.gz ${FILE_SERVER_URL}/upload
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