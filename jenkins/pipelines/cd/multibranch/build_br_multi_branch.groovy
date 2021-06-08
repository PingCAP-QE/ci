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

def BUILD_URL = 'git@github.com:pingcap/br.git'
def slackcolor = 'good'
def githash

def BUILD_NUMBER = "${env.BUILD_NUMBER}"

try {

    node("${GO_TEST_SLAVE}") {

        def gws = pwd()

        stage("Checkout") {
            dir("/home/jenkins/agent/git/br") {
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
            }
        }

        stage("Build") {
            dir("go/src/github.com/pingcap/br") {
                deleteDir()
                container("golang") {
                    timeout(20) {
                        sh """
                            cp -R /home/jenkins/agent/git/br/. ./
                            GOPATH=\$GOPATH:${gws}/go make build
                        """
                    }
                }
            }
        }

        stage("Upload") {
            container("golang") {
                dir("go/src/github.com/pingcap/br") {
                    // 供 release 到外部使用
                    def refspath = "refs/pingcap/br/${env.BRANCH_NAME}/sha1"
                    def filepath = "builds/pingcap/br/${env.BRANCH_NAME}/${githash}/centos7/br.tar.gz"

                    timeout(10) {
                        sh """
                        echo "${githash}" > sha1
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload

                        tar --exclude=br.tar.gz -czvf br.tar.gz ./bin
                        curl -F ${filepath}=@br.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
        if ("${env.BRANCH_NAME}" == "master"){
            stage("Upload latest"){
                container("golang") {
                    dir("go/src/github.com/pingcap/br") {
                        // 供内部使用
                        def latestref = "builds/pingcap/br/latest/centos7/sha1"
                        def latestpath = "builds/pingcap/br/latest/centos7/br.tar.gz"

                        timeout(10) {
                            sh """
                            echo "${githash}" > sha1
                            curl -F ${latestref}=@sha1 ${FILE_SERVER_URL}/upload

                            tar --exclude=br.tar.gz -czvf br.tar.gz ./bin
                            curl -F ${latestpath}=@br.tar.gz ${FILE_SERVER_URL}/upload
                            """
                        }
                    }
                }
            }
        }

        if ("${env.BRANCH_NAME}" == "master" || "${env.BRANCH_NAME}" == "release-4.0" || "${env.BRANCH_NAME}" == "release-5.0"){
            stage("Post build tests") {
                def default_params = [
                    booleanParam(name: 'force', value: true),
                    booleanParam(name: 'triggered_by_build_br_multi_branch', value: true),
                    string(name: 'build_br_multi_branch_release_branch', value: "${env.BRANCH_NAME}"),
                    string(name: 'build_br_multi_branch_ghpr_actual_commit', value: "${githash}"),
                ]
                build(job: "br_ghpr_unit_and_integration_test", parameters: default_params)
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

    if (currentBuild.result != "SUCCESS" ) {
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
