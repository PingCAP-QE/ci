/*
* @RELEASE_TAG
*/
def libs

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
        }
    }

    stage("Build") {
        build_para = [:]
        build_para["tidb"] = TIDB_HASH
        build_para["tikv"] = TIKV_HASH
        build_para["pd"] = PD_HASH
        build_para["tiflash"] = TIFLASH_HASH
        build_para["FORCE_REBUILD"] = true
        build_para["RELEASE_TAG"] = RELEASE_TAG
        build_para["PLATFORM"] = PLATFORM
        build_para["OS"] = OS
        build_para["ARCH"] = ARCH
        build_para["FILE_SERVER_URL"] = FILE_SERVER_URL
        
        builds = libs.create_enterprise_builds(build_para)

        parallel builds
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e ) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}