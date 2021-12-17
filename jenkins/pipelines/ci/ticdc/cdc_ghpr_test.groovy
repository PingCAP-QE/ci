echo "Job start..."

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

isNeedGo1160 = false
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

def run_with_pod(Closure body) {
    def label = "cdc_ghpr_unit_test_1.13"
    pod_go_docker_image = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
    if (isNeedGo1160) {
        println "Use go1.16.4"
        pod_go_docker_image = "hub.pingcap.net/pingcap/centos7_golang-1.16:latest"
        label = "cdc_ghpr_unit_test_1.16"
    } else {
        println "Use go1.13.7"
    }
    podTemplate(label: label,
            instanceCap: 5,
            containers: [containerTemplate(
                    name: 'golang', alwaysPullImage: true,
                    image: "${pod_go_docker_image}", ttyEnabled: true,
                    resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                    resourceLimitCpu: '16000m', resourceLimitMemory: "30Gi",
                    command: '/bin/sh -c', args: 'cat',
                    envVars: [containerEnvVar(key: 'GOMODCACHE', value: '/nfs/cache/mod'),
                              containerEnvVar(key: 'GOPATH', value: '/go'),
                              containerEnvVar(key: 'GOCACHE', value: '/nfs/cache/go-build')],
            )],
            volumes: [
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                    nfsVolume(mountPath: '/nfs/cache', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs', readOnly: false),
                    nfsVolume(mountPath: '/go/pkg', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/gopath/pkg', readOnly: false),
            ],
    ) {
        node(label) {
            println("${NODE_NAME}")
            println "current pod use CPU 4000m & Memory 8Gi"
            body()
        }
    }
}

catchError {
    run_with_pod() {
        stage('Prepare') {
            def ws = pwd()
            deleteDir()

            dir("${ws}/go/src/github.com/pingcap/tiflow") {
                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/tiflow"
                    deleteDir()
                }
                try {
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tiflow.git']]]
                } catch (info) {
                    retry(2) {
                        echo "checkout failed, retry.."
                        sleep 5
                        if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tiflow.git']]]
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

            stash includes: "go/src/github.com/pingcap/tiflow/**", name: "ticdc", useDefaultExcludes: false
        }

        catchError {
            stage("Unit Test") {
                run_with_pod() {
                    container("golang") {
                        def ws = pwd()
                        deleteDir()
                        unstash 'ticdc'

                        dir("go/src/github.com/pingcap/tiflow") {
                            sh """
                                rm -rf /tmp/tidb_cdc_test
                                mkdir -p /tmp/tidb_cdc_test
                                GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make test
                                rm -rf cov_dir
                                mkdir -p cov_dir
                                ls /tmp/tidb_cdc_test
                                cp /tmp/tidb_cdc_test/cov*out cov_dir
                            """
                            sh """
                                tail /tmp/tidb_cdc_test/cov*
                            """
                        }
                        stash includes: "go/src/github.com/pingcap/tiflow/cov_dir/**", name: "unit_test", useDefaultExcludes: false
                    }
                }
            }

            currentBuild.result = "SUCCESS"
        }

        stage('Coverage') {
            node("${GO_TEST_SLAVE}") {
                def ws = pwd()
                deleteDir()
                unstash 'ticdc'
                unstash 'unit_test'

                dir("go/src/github.com/pingcap/tiflow") {
                    container("golang") {
                        archiveArtifacts artifacts: 'cov_dir/*', fingerprint: true
                        withCredentials([string(credentialsId: 'codecov-token-ticdc', variable: 'CODECOV_TOKEN')]) {
                            timeout(30) {
                                sh '''
                            rm -rf /tmp/tidb_cdc_test
                            mkdir -p /tmp/tidb_cdc_test
                            cp cov_dir/* /tmp/tidb_cdc_test
                            set +x
                            BUILD_NUMBER=${BUILD_NUMBER} CODECOV_TOKEN="${CODECOV_TOKEN}" COVERALLS_TOKEN="${COVERALLS_TOKEN}" GOPATH=${ws}/go:\$GOPATH PATH=${ws}/go/bin:/go/bin:\$PATH JenkinsCI=1 make unit_test_coverage
                            set -x
                            '''
                            }
                        }
                    }
                }
            }
        }

        stage('Summary') {
            def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
            def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
                    "${ghprbPullLink}" + "\n" +
                    "${ghprbPullDescription}" + "\n" +
                    "Unit Test Result: `${currentBuild.result}`" + "\n" +
                    "Elapsed Time: `${duration} mins` " + "\n" +
                    "${env.RUN_DISPLAY_URL}"

            if (currentBuild.result != "SUCCESS") {
                slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
            }
        }

    }
}




