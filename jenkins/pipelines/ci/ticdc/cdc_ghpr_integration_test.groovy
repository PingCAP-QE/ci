echo "release test: ${params.containsKey("release_test")}"

if (params.containsKey("release_test")) {
    ghprbActualCommit = params.release_test__cdc_commit
    ghprbTargetBranch = params.release_test__release_branch
    ghprbPullId = ""
    ghprbCommentBody = ""
    ghprbPullLink = "release-test"
    ghprbPullTitle = "release-test"
    ghprbPullDescription = "release-test"
}

def ciRepoUrl = "https://github.com/PingCAP-QE/ci.git"
def ciRepoBranch = "main"

def specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
if (ghprbPullId == null || ghprbPullId == "") {
    specStr = "+refs/heads/*:refs/remotes/origin/*"
}

@NonCPS
boolean isMoreRecentOrEqual(String a, String b) {
    if (a == b) {
        return true
    }

    [a, b]*.tokenize('.')*.collect { it as int }.with { u, v ->
        Integer result = [u, v].transpose().findResult { x, y -> x <=> y ?: null } ?: u.size() <=> v.size()
        return (result == 1)
    }
}

string trimPrefix = {
    it.startsWith('release-') ? it.minus('release-').split("-")[0] : it
}

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = false
releaseBranchUseGo1160 = "release-5.1"

if (!isNeedGo1160) {
    isNeedGo1160 = isBranchMatched(["master", "hz-poc"], ghprbTargetBranch)
}
if (!isNeedGo1160 && ghprbTargetBranch.startsWith("release-")) {
    isNeedGo1160 = isMoreRecentOrEqual(trimPrefix(ghprbTargetBranch), trimPrefix(releaseBranchUseGo1160))
    if (isNeedGo1160) {
        println "targetBranch=${ghprbTargetBranch}  >= ${releaseBranchUseGo1160}"
    }
}

if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
    POD_GO_DOCKER_IMAGE = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
} else {
    println "This build use go1.13"
    POD_GO_DOCKER_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"
println "POD_GO_DOCKER_IMAGE=${POD_GO_DOCKER_IMAGE}"

catchError {
    withEnv(['CODECOV_TOKEN=c6ac8b7a-7113-4b3f-8e98-9314a486e41e',
             'COVERALLS_TOKEN=HTRawMvXi9p5n4OyBvQygxd5iWjNUKd1o']) {
        node("${GO_TEST_SLAVE}") {
            stage('Prepare') {
                def ws = pwd()
                deleteDir()

                dir("${ws}/go/src/github.com/pingcap/ticdc") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/ticdc"
                        deleteDir()
                    }
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/ticdc.git']]]
                    } catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/ticdc.git']]]
                        }
                    }
                    sh "git checkout -f ${ghprbActualCommit}"
                }

                dir("${ws}/go/src/github.com/pingcap/ci") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/ci"
                        deleteDir()
                    }
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: "${ciRepoBranch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[refspec: specStr, url: "${ciRepoUrl}"]]]
                    } catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/ci"
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: "${ciRepoBranch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[refspec: specStr, url: "${ciRepoUrl}"]]]
                        }
                    }

                }

                stash includes: "go/src/github.com/pingcap/ticdc/**", name: "ticdc", useDefaultExcludes: false
            }

            def script_path = "go/src/github.com/pingcap/ci/jenkins/pipelines/ci/ticdc/integration_test_common.groovy"
            def common = load script_path
            catchError {
                common.prepare_binaries()

                def label = "cdc-integration-test"
                podTemplate(label: label,
                        idleMinutes: 0,
                        containers: [
                                containerTemplate(
                                        name: 'golang', alwaysPullImage: true,
                                        image: "${POD_GO_DOCKER_IMAGE}", ttyEnabled: true,
                                        resourceRequestCpu: '2000m', resourceRequestMemory: '12Gi',
                                        command: '/bin/sh -c', args: 'cat',
                                        envVars: [containerEnvVar(key: 'GOMODCACHE', value: '/nfs/cache/mod'),
                                                  containerEnvVar(key: 'GOPATH', value: '/go')],
                                ),
                        ],
                        volumes: [
                                nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                                        serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                                nfsVolume(mountPath: '/nfs/cache', serverAddress: '172.16.5.22',
                                        serverPath: '/mnt/ci.pingcap.net-nfs', readOnly: false),
                                nfsVolume(mountPath: '/go/pkg', serverAddress: '172.16.5.22',
                                        serverPath: '/mnt/ci.pingcap.net-nfs/gopath/pkg', readOnly: false),
                                emptyDirVolume(mountPath: '/tmp', memory: true),
                                emptyDirVolume(mountPath: '/home/jenkins', memory: true)
                        ],
                ) {
                    common.tests("mysql", label)
                }

                common.coverage()
                currentBuild.result = "SUCCESS"
            }

            stage('Summary') {
                def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
                def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
                        "${ghprbPullLink}" + "\n" +
                        "${ghprbPullDescription}" + "\n" +
                        "Integration Test Result: `${currentBuild.result}`" + "\n" +
                        "Elapsed Time: `${duration} mins` " + "\n" +
                        "${env.RUN_DISPLAY_URL}"

                if (currentBuild.result != "SUCCESS") {
                    slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
                }
            }
        }
    }
}



