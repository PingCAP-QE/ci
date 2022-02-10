/*
* @TIDB_HASH
* @TIKV_HASH
* @PD_HASH
* @BINLOG_HASH
* @LIGHTNING_HASH
* @TOOLS_HASH
* @CDC_HASH
* @BR_HASH
* @IMPORTER_HASH
* @TIFLASH_HASH
* @RELEASE_TAG
* @PRE_RELEASE
* @FORCE_REBUILD
* @NGMonitoring_HASH
* @TIKV_PRID
*/

GO_BIN_PATH="/usr/local/go/bin"
def boolean tagNeedUpgradeGoVersion(String tag) {
    if (tag.startsWith("v") && tag > "v5.1") {
        println "tag=${tag} need upgrade go version"
        return true
    }
    return false
}

def isNeedGo1160 = tagNeedUpgradeGoVersion(RELEASE_TAG)
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"


def slackcolor = 'good'
def githash
os = "linux"
arch = "amd64"
platform = "centos7"
def libs

// 为了和之前兼容，linux amd 的 build 和上传包的内容都采用 build_xxx_multi_branch 中的 build 脚本
// linux arm 和 Darwin amd 保持不变
try {

    // stage prepare
    // stage build
    stage("Validating HASH") {
        node("${GO_BUILD_SLAVE}") {
            container("golang") {
                def ws = pwd()
                deleteDir()
                stage("GO node") {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    println "${ws}"
                    if (TIDB_HASH.length() < 40 || TIKV_HASH.length() < 40 || PD_HASH.length() < 40 || BINLOG_HASH.length() < 40 || TIFLASH_HASH.length() < 40 || TOOLS_HASH.length() < 40 || BR_HASH.length() < 40 || CDC_HASH.length() < 40) {
                        println "build must be used with githash."
                        sh "exit 2"
                    }
                    if (IMPORTER_HASH.length() < 40 && RELEASE_TAG < "v5.2.0"){
                        println "build must be used with githash."
                    sh "exit 2"
                    }
                }
                checkout scm
                libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
            }
        }
    }

    stage("Build") {
        build_para = [:]
        build_para["tidb-ctl"] = TIDB_CTL_HASH
        build_para["tidb"] = TIDB_HASH
        build_para["tikv"] = TIKV_HASH
        build_para["tidb-binlog"] = BINLOG_HASH
        build_para["tidb-tools"] = TOOLS_HASH
        build_para["pd"] = PD_HASH
        build_para["ticdc"] = CDC_HASH
        build_para["importer"] = IMPORTER_HASH
        build_para["br"] = BR_HASH
        build_para["dumpling"] = DUMPLING_HASH
        build_para["ng-monitoring"] = NGMonitoring_HASH
        build_para["enterprise-plugin"] = RELEASE_BRANCH
        build_para["tiflash"] = TIFLASH_HASH
        build_para["FORCE_REBUILD"] = params.FORCE_REBUILD
        build_para["RELEASE_TAG"] = RELEASE_TAG
        build_para["PLATFORM"] = platform
        build_para["OS"] = os
        build_para["ARCH"] = arch
        build_para["FILE_SERVER_URL"] = FILE_SERVER_URL
        build_para["GIT_PR"] = ""

        builds = libs.create_builds(build_para)
        parallel builds
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}

stage('Summary') {
    echo "Send slack here ..."
    //slackSend channel: "", color: "${slackcolor}", teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    // if (currentBuild.result != "SUCCESS") {
    //     slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    // }
}
