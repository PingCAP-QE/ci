def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = isBranchMatched(["master", "release-5.1"], env.BRANCH_NAME)
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

def BUILD_URL = 'git@github.com:tikv/pd.git'
def slackcolor = 'good'
def githash
def master_branch_node = "${GO_BUILD_SLAVE}"
def branchNodeMap = [
    "master" : master_branch_node,
    "release-2.0" : "build_go1130",
    "release-2.1" : "build_go1130",
    "release-3.0" : "build_go1130",
]

try {
    // 如果不在 map 里，如 release-3.1 分支或者 tag 分支，就使用和 master 一样的环境
    node(branchNodeMap.get("${env.BRANCH_NAME}".toString(), master_branch_node)) {
        def ws = pwd()
        //deleteDir()

        stage("Debug Info"){
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
        }

        stage("Checkout") {
            dir("go/src/github.com/pingcap/pd") {
                deleteDir()
                // 如果不是 TAG，直接传 branch 给下面的 checkout 语句； 否则就应该 checkout 到 refs/tags 下 .
                // 值得注意的是，即使传入的是 TAG，环境变量里的 BRANCH_NAME 和 TAG_NAME 同时会是 TAG 名，如 v3.0.0
                def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
                println branch
                
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
                
                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                
                sh"""
                git branch
                """
            }
        }

        stage("Build") {
            dir("go/src/github.com/pingcap/pd") {
                container("golang") {
                    timeout(10) {
                        sh """
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        GOPATH=${ws}/go make
                        GOPATH=${ws}/go make tools
                        """
                    }
                }
            }
        }

        stage("Upload") {
            dir("go/src/github.com/pingcap/pd") {
                def refspath = "refs/pingcap/pd/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/pd/${githash}/centos7/pd-server.tar.gz"
                container("golang") {
                    timeout(10) {
                        sh """
                        echo "${githash}" > sha1
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        tar --exclude=pd-server.tar.gz -czvf pd-server.tar.gz *
                        curl -F ${filepath}=@pd-server.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
    }
    stage("Push pd Docker") {
        build job: 'build_image_hash', wait: false, parameters: [[$class: 'StringParameterValue', name: 'REPO', value: "pd"], [$class: 'StringParameterValue', name: 'COMMIT_ID', value: githash], [$class: 'StringParameterValue', name: 'IMAGE_TAG', value: env.BRANCH_NAME]]
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}

stage('Summary') {
    echo "Send slack here ..."
    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}"
    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}