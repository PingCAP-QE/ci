/*
* @RELEASE_TAG
*/
properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIDB_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PD_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIFLASH_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'ENTERPRISE_PLUGIN_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_PRID',
                        trim: true
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD'
                ),
        ])
])

def libs

def PLATFORM = "centos7"
def OS = "linux"

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
        build_para_arm = [:]
        build_para_arm["tidb"] = TIDB_HASH
        build_para_arm["tikv"] = TIKV_HASH
        build_para_arm["pd"] = PD_HASH
        build_para_arm["tiflash"] = TIFLASH_HASH
        build_para_arm["enterprise-plugin"] = ENTERPRISE_PLUGIN_HASH
        build_para_arm["FORCE_REBUILD"] = FORCE_REBUILD
        build_para_arm["RELEASE_TAG"] = RELEASE_TAG
        build_para_arm["PLATFORM"] = PLATFORM
        build_para_arm["OS"] = OS
        build_para_arm["ARCH"] = "arm64"
        build_para_arm["FILE_SERVER_URL"] = FILE_SERVER_URL
        build_para_arm["GIT_PR"] = TIKV_PRID

        builds_arm = libs.create_enterprise_builds(build_para_arm)


        build_para_amd = [:]
        build_para_amd["tidb"] = TIDB_HASH
        build_para_amd["tikv"] = TIKV_HASH
        build_para_amd["pd"] = PD_HASH
        build_para_amd["tiflash"] = TIFLASH_HASH
        build_para_amd["enterprise-plugin"] = ENTERPRISE_PLUGIN_HASH
        build_para_amd["FORCE_REBUILD"] = FORCE_REBUILD
        build_para_amd["RELEASE_TAG"] = RELEASE_TAG
        build_para_amd["PLATFORM"] = PLATFORM
        build_para_amd["OS"] = OS
        build_para_amd["ARCH"] = "amd64"
        build_para_amd["FILE_SERVER_URL"] = FILE_SERVER_URL
        build_para_amd["GIT_PR"] = TIKV_PRID


        builds_amd = libs.create_enterprise_builds(build_para_amd)

        builds = builds_arm + builds_amd

        parallel builds
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e ) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}
