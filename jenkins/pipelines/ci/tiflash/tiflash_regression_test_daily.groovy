def targetBranch = params.getOrDefault("ghprbTargetBranch", "")
if (targetBranch != "") {
    branch = targetBranch
}
if (version == "") {
    version = "latest"
}

// parse from github comment
if (params.getOrDefault("ghprbCommentBody", "") != "") {
    // parse branch
    def m1 = ghprbCommentBody =~ /branch\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m1) {
        branch = "${m1[0][1]}"
    }
    m1 = null
    // parse branch
    def m2 = ghprbCommentBody =~ /version\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m2) {
        version = "${m2[0][1]}"
    }
    m2 = null
}
println "branch=${branch}, version=${version}"

if (currentBuild.description == null || currentBuild.description != "") {
    currentBuild.description = "${desc} branch=${branch} version=${version}"
}

def checkout_name = params.getOrDefault("ghprbActualCommit", "")
if (checkout_name == "") {
    checkout_name = branch
}
if (checkout_name in ["planner_refactory"]) {
    checkout_name = "master"
}

def label = "test-tiflash-regression"

podTemplate(name: label, label: label, instanceCap: 5, cloud: "kubernetes-ksyun", namespace: "jenkins-tiflash-schrodinger", idleMinutes: 5, nodeSelector: "kubernetes.io/arch=amd64", containers: [
        containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true,
                resourceRequestCpu: '2000m', resourceRequestMemory: '8Gi'),
        containerTemplate(name: 'tiflash-docker', image: 'hub.pingcap.net/tiflash/docker:build-essential-java',
                envVars: [
                        envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
                ], alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'docker-ops-ci', image: 'hub.pingcap.net/tiflash/ops-ci:v11',
                envVars: [
                        envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
                ], alwaysPullImage: true, ttyEnabled: true, command: 'cat', resourceRequestCpu: '20000m', resourceRequestMemory: '20Gi'),
]) {
    node(label) {
        container("tiflash-docker") {
            sh """
            killall -9 tidb-server || true
            killall -9 tikv-server || true
            killall -9 pd-server || true
            killall -9 theflash || true
            killall -9 tiflash || true
            killall -9 tikv-server-rngine || true
            pkill -f 'java*' || true
            """

            dir("/home/jenkins/agent/git/tiflash/") {
                sh "chown -R 1000:1000 ./"

                def ws = pwd()
                deleteDir()

                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }
                def pi = params.getOrDefault("ghprbPullId", "0")

                checkout changelog: false, poll: false, scm: [
                        $class                           : 'GitSCM',
                        branches                         : [[name: checkout_name]],
                        doGenerateSubmoduleConfigurations: false,
                        userRemoteConfigs                : [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap-inc/tiflash-scripts.git']],
                        extensions: [[
                            $class: 'CloneOption',
                            shallow: true,
                            depth:   1,
                            timeout: 10
                        ]],
                ]

                sleep time: 5, unit: 'SECONDS'

                sh """
                git rev-parse HEAD
                """

                dailyTest = load 'regression_test/daily.groovy'
                if (dailyTest.hasProperty('config')) {
                    println("set cloud to idc-5-70")
                    dailyTest.config.cloud = 'kubernetes-ksyun'
                    dailyTest.config.label = "test-tiflash-regression-v11-cloud-5-70_${BUILD_NUMBER}"
                }
                if (branch in ["planner_refactory"]) {
                    println "this is a feature branch"
                        tidb_commit_hash = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/master/sha1").trim()
                        tikv_commit_hash = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tikv/master/sha1").trim()
                        pd_commit_hash = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/master/sha1").trim()
                }

                println "pipeline: $pipeline"
            }
        }
    }

    dailyTest.runDailyIntegrationTest3(branch, version, tidb_commit_hash, tikv_commit_hash, pd_commit_hash, tiflash_commit_hash, checkout_name, notify, idleMinutes)
}
