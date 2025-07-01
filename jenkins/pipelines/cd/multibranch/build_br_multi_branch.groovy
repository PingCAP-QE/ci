/*
    Deprecate this file, br has been merged into tidb repo since v5.2 (inclde v5.2.0)
    Get the br binary from tidb repo instead of br repo,
    build_tidb_multi_branch.groovy will build br binary for all branches
*/

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
    GO_BUILD_SLAVE = "build_go1130_memvolume"
}

println "This build use ${goVersion}"

def BUILD_URL = 'git@github.com:pingcap/br.git'
def slackcolor = 'good'
def githash

def BUILD_NUMBER = "${env.BUILD_NUMBER}"


def release_one(repo,hash) {
    def binary = "builds/pingcap/test/${env.BRANCH_NAME}/${repo}/${hash}/centos7/${repo}-linux-arm64.tar.gz"
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

        def gws = pwd()

        stage("Checkout") {
            dir("/home/jenkins/agent/git/br") {
                def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
                println branch
                println env.TAG_NAME

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
                    // release_one("br","${githash}")

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
