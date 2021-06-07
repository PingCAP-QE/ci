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

def ciRepeUrl = "https://github.com/PingCAP-QE/ci.git"
def ciRepoBranch = "main"

def specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
if (ghprbPullId == null || ghprbPullId == "") {
    specStr = "+refs/heads/*:refs/remotes/origin/*"
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

def isNeedGo1160 = isBranchMatched(["master", "release-5.1"], ghprbTargetBranch)
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

catchError {
    withEnv(['CODECOV_TOKEN=c6ac8b7a-7113-4b3f-8e98-9314a486e41e',
             'COVERALLS_TOKEN=HTRawMvXi9p5n4OyBvQygxd5iWjNUKd1o']){
        node ("${GO_TEST_SLAVE}") {
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
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: "${ciRepoBranch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[refspec: specStr, url: "${ciRepeUrl}"]]]
                    } catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/ci"
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: "${ciRepoBranch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[refspec: specStr, url: "${ciRepeUrl}"]]]
                        }
                    }

                }

                stash includes: "go/src/github.com/pingcap/ticdc/**", name: "ticdc", useDefaultExcludes: false
            }

            def script_path = "go/src/github.com/pingcap/ci/jenkins/pipelines/ci/ticdc/integration_test_common.groovy"
            def common = load script_path

            // HACK! Download jks by injecting RACK_COMMAND
            // https://git.io/JJZXX -> https://github.com/pingcap/ticdc/raw/6e62afcfecc4e3965d8818784327d4bf2600d9fa/tests/_certificates/kafka.server.keystore.jks
            // https://git.io/JJZXM -> https://github.com/pingcap/ticdc/raw/6e62afcfecc4e3965d8818784327d4bf2600d9fa/tests/_certificates/kafka.server.truststore.jks
            def download_jks = 'curl -sfL https://git.io/JJZXX -o /tmp/kafka.server.keystore.jks && curl -sfL https://git.io/JJZXM -o /tmp/kafka.server.truststore.jks'

            catchError {
                def KAFKA_TAG = "2.12-2.4.1"
                def KAFKA_VERSION = "2.4.1"
                // parse kafka tag
                def m1 = ghprbCommentBody =~ /kafka-tag\s*=\s*([^\s\\]+)(\s|\\|$)/
                if (m1) {
                    KAFKA_TAG = "${m1[0][1]}"
                }
                m1 = null
                println "KAFKA_TAG=${KAFKA_TAG}"

                // parse kafka version
                def m2 = ghprbCommentBody =~ /kafka-version\s*=\s*([^\s\\]+)(\s|\\|$)/
                if (m2) {
                    KAFKA_VERSION = "${m2[0][1]}"
                }
                m2 = null
                println "KAFKA_VERSION=${KAFKA_VERSION}"

                env.KAFKA_VERSION = "${KAFKA_VERSION}"

                common.prepare_binaries()

                def label = "cdc-kafka-integration-${UUID.randomUUID().toString()}"
                podTemplate(label: label, idleMinutes: 0,
                        containers: [
                                containerTemplate(name: 'golang',alwaysPullImage: false, image: "${GO_DOCKER_IMAGE}",
                                        resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                                        ttyEnabled: true, command: 'cat'),
                                containerTemplate(name: 'zookeeper',alwaysPullImage: false, image: 'wurstmeister/zookeeper',
                                        resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                                        ttyEnabled: true),
                                containerTemplate(
                                        name: 'kafka',
                                        image: "wurstmeister/kafka:${KAFKA_TAG}",
                                        resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                                        ttyEnabled: true,
                                        alwaysPullImage: false,
                                        envVars: [
                                                envVar(key: 'KAFKA_MESSAGE_MAX_BYTES', value: '1073741824'),
                                                envVar(key: 'KAFKA_REPLICA_FETCH_MAX_BYTES', value: '1073741824'),
                                                envVar(key: 'KAFKA_BROKER_ID', value: '1'),
                                                envVar(key: 'RACK_COMMAND', value: download_jks),
                                                envVar(key: 'KAFKA_LISTENERS', value: 'SSL://127.0.0.1:9093,PLAINTEXT://127.0.0.1:9092'),
                                                envVar(key: 'KAFKA_ADVERTISED_LISTENERS', value: 'SSL://127.0.0.1:9093,PLAINTEXT://127.0.0.1:9092'),
                                                envVar(key: 'KAFKA_SSL_KEYSTORE_LOCATION', value: '/tmp/kafka.server.keystore.jks'),
                                                envVar(key: 'KAFKA_SSL_KEYSTORE_PASSWORD', value: 'test1234'),
                                                envVar(key: 'KAFKA_SSL_KEY_PASSWORD', value: 'test1234'),
                                                envVar(key: 'KAFKA_SSL_TRUSTSTORE_LOCATION', value: '/tmp/kafka.server.truststore.jks'),
                                                envVar(key: 'KAFKA_SSL_TRUSTSTORE_PASSWORD', value: 'test1234'),
                                                envVar(key: 'ZK', value: 'zk'),
                                                envVar(key: 'KAFKA_ZOOKEEPER_CONNECT', value: 'localhost:2181'),
                                        ]
                                )],
                        volumes:[
                                emptyDirVolume(mountPath: '/tmp', memory: true),
                                emptyDirVolume(mountPath: '/home/jenkins', memory: true)
                        ]
                ) {
                    common.tests("kafka", label)
                }

                currentBuild.result = "SUCCESS"
            }

            stage('Summary') {
                def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
                def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
                        "${ghprbPullLink}" + "\n" +
                        "${ghprbPullDescription}" + "\n" +
                        "Integration Kafka Test Result: `${currentBuild.result}`" + "\n" +
                        "Elapsed Time: `${duration} mins` " + "\n" +
                        "${env.RUN_DISPLAY_URL}"

                if (currentBuild.result != "SUCCESS") {
                    slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
                }
            }
        }
    }
}






