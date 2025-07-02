/*
* @TIDB_HASH
* @TIKV_HASH
* @PD_HASH
* @BINLOG_HASH
* @LIGHTNING_HASH
* @TOOLS_HASH
* @CDC_HASH
* @DM_HASH
* @BR_HASH
* @TIFLASH_HASH
* @RELEASE_TAG
* @FORCE_REBUILD
* @RELEASE_BRANCH
* @NGMonitoring_HASH
* @TIKV_PRID
* @PLATFORM
* @ARCH
* @OS
*/

def libs

try {
    stage("Load libs") {
        node("${GO_BUILD_SLAVE}") {
            def ws = pwd()
            deleteDir()
            checkout scm
            libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
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
        build_para["dm"] = DM_HASH
        build_para["br"] = BR_HASH
        build_para["dumpling"] = DUMPLING_HASH
        build_para["ng-monitoring"] = NGMonitoring_HASH
        build_para["tiflash"] = TIFLASH_HASH
        build_para["FORCE_REBUILD"] = params.FORCE_REBUILD
        build_para["RELEASE_TAG"] = RELEASE_TAG
        build_para["PLATFORM"] = PLATFORM
        build_para["OS"] = OS
        build_para["ARCH"] = ARCH
        build_para["FILE_SERVER_URL"] = FILE_SERVER_URL
        build_para["GIT_PR"] = TIKV_PRID
        build_para["PRE_RELEASE"] = PRE_RELEASE
        build_para["RELEASE_BRANCH"] = RELEASE_BRANCH


        builds = libs.create_builds(build_para)

        parallel builds
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}
