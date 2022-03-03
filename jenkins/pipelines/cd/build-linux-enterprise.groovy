/*
* @RELEASE_TAG
*/
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
            sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
            TIDB_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            TIKV_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            PD_HASH = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            TIFLASH_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
        }
    }

    stage("Build") {
        build_para_arm = [:]
        build_para_arm["tidb"] = TIDB_HASH
        build_para_arm["tikv"] = TIKV_HASH
        build_para_arm["pd"] = PD_HASH
        build_para_arm["tiflash"] = TIFLASH_HASH
        build_para_arm["FORCE_REBUILD"] = true
        build_para_arm["RELEASE_TAG"] = RELEASE_TAG
        build_para_arm["PLATFORM"] = PLATFORM
        build_para_arm["OS"] = OS
        build_para_arm["ARCH"] = "arm64"
        build_para_arm["FILE_SERVER_URL"] = FILE_SERVER_URL
        build_para_arm["GIT_PR"] = ""
        
        builds_arm = libs.create_enterprise_builds(build_para_arm)

    
        build_para_amd = [:]
        build_para_amd["tidb"] = TIDB_HASH
        build_para_amd["tikv"] = TIKV_HASH
        build_para_amd["pd"] = PD_HASH
        build_para_amd["tiflash"] = TIFLASH_HASH
        build_para_amd["FORCE_REBUILD"] = true
        build_para_amd["RELEASE_TAG"] = RELEASE_TAG
        build_para_amd["PLATFORM"] = PLATFORM
        build_para_amd["OS"] = OS
        build_para_amd["ARCH"] = "amd64"
        build_para_amd["FILE_SERVER_URL"] = FILE_SERVER_URL
        build_para_amd["GIT_PR"] = ""


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