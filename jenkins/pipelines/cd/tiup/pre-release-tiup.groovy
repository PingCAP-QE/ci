/*
* @ARCH_ARM
* @ARCH_X86
* @ARCH_MAC
* @RELEASE_BRANCH
* @RELEASE_TAG
* @FORCE_REBUILD
* @TIUP_MIRRORS
* @TIKV_BUMPVERION_HASH
* @TIKV_BUMPVERSION_PRID
*/

tidb_sha1=""
tikv_sha1=""
pd_sha1=""
tiflash_sha1="" 
br_sha1=""
binlog_sha1=""
lightning_sha1=""
importer_sha1=""
tools_sha1=""
cdc_sha1=""
dumpling_sha1=""

def get_sha() {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    if (RELEASE_TAG >= "v5.2.0") {
        binlog_sha1 = tidb_sha1
    } else {
        binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    }
    lightning_sha1 = br_sha1
    importer_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=importer -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=master -s=${FILE_SERVER_URL}").trim()
    cdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ticdc -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()

    if (TIKV_BUMPVERION_HASH.length() == 40) {
        return tikv_sha1 = TIKV_BUMPVERION_HASH
    }
}

node("build_go1130") {
    container("golang") {
        stage("get hash") {
            get_sha()
        }
    }
}
stage('Build') {

    builds = [:]
    if (params.ARCH_ARM) {
        builds["Build on linux/arm64"] = {
            build job: "optimization-build-tidb-linux-arm",
                    wait: true,
                    parameters: [
                            [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                            [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                            [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                            [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: binlog_sha1],
                            [$class: 'StringParameterValue', name: 'LIGHTNING_HASH', value: lightning_sha1],
                            [$class: 'StringParameterValue', name: 'IMPORTER_HASH', value: importer_sha1],
                            [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tools_sha1],
                            [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                            [$class: 'StringParameterValue', name: 'BR_HASH', value: br_sha1],
                            [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: dumpling_sha1],
                            [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                            [$class: 'BooleanParameterValue', name: 'SKIP_TIFLASH', value: false],
                            [$class: 'BooleanParameterValue', name: 'BUILD_TIKV_IMPORTER', value: false],
                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                            [$class: 'StringParameterValue', name: 'TIKV_PRID', value: TIKV_BUMPVERSION_PRID],
                            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                    ]
        }
    }
    if (params.ARCH_MAC) {
        builds["Build on darwin/amd64"] = {
            build job: "optimization-build-tidb-darwin-amd",
                    wait: true,
                    parameters: [
                            [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                            [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                            [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                            [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: binlog_sha1],
                            [$class: 'StringParameterValue', name: 'LIGHTNING_HASH', value: lightning_sha1],
                            [$class: 'StringParameterValue', name: 'IMPORTER_HASH', value: importer_sha1],
                            [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tools_sha1],
                            [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                            [$class: 'StringParameterValue', name: 'BR_HASH', value: br_sha1],
                            [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: dumpling_sha1],
                            [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                            [$class: 'BooleanParameterValue', name: 'SKIP_TIFLASH', value: false],
                            [$class: 'BooleanParameterValue', name: 'BUILD_TIKV_IMPORTER', value: false],
                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                            [$class: 'StringParameterValue', name: 'TIKV_PRID', value: TIKV_BUMPVERSION_PRID],
                            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                    ]
        }
    }

    if (params.ARCH_X86) {
        builds["Build on linux/amd64"] = {
            build job: "optimization-build-tidb-linux-amd",
                    wait: true,
                    parameters: [
                            [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                            [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                            [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                            [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: binlog_sha1],
                            [$class: 'StringParameterValue', name: 'LIGHTNING_HASH', value: lightning_sha1],
                            [$class: 'StringParameterValue', name: 'IMPORTER_HASH', value: importer_sha1],
                            [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tools_sha1],
                            [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                            [$class: 'StringParameterValue', name: 'BR_HASH', value: br_sha1],
                            [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: dumpling_sha1],
                            [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                            [$class: 'BooleanParameterValue', name: 'SKIP_TIFLASH', value: false],
                            [$class: 'BooleanParameterValue', name: 'BUILD_TIKV_IMPORTER', value: false],
                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                            [$class: 'StringParameterValue', name: 'TIKV_PRID', value: TIKV_BUMPVERSION_PRID],
                            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                    ]
        }
    }

    if (params.ARCH_MAC_ARM) {
        builds["Build on darwin/arm64"] = {
            build job: "optimization-build-tidb-darwin-arm",
                    wait: true,
                    parameters: [
                            [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                            [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                            [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                            [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: binlog_sha1],
                            [$class: 'StringParameterValue', name: 'LIGHTNING_HASH', value: lightning_sha1],
                            [$class: 'StringParameterValue', name: 'IMPORTER_HASH', value: importer_sha1],
                            [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tools_sha1],
                            [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                            [$class: 'StringParameterValue', name: 'BR_HASH', value: br_sha1],
                            [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: dumpling_sha1],
                            [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                            [$class: 'BooleanParameterValue', name: 'SKIP_TIFLASH', value: false],
                            [$class: 'BooleanParameterValue', name: 'BUILD_TIKV_IMPORTER', value: false],
                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                            [$class: 'StringParameterValue', name: 'TIKV_PRID', value: TIKV_BUMPVERSION_PRID],
                            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                    ]
        }
    }
    parallel builds
}

stage('releaese tiup') {
    build job: "tiup-mirror-update-test-hotfix",
        wait: true,
        parameters: [
                [$class: 'StringParameterValue', name: 'TIDB_TAG', value: tidb_sha1],
                [$class: 'StringParameterValue', name: 'TIKV_TAG', value: tikv_sha1],
                [$class: 'StringParameterValue', name: 'PD_TAG', value: pd_sha1],
                [$class: 'StringParameterValue', name: 'BINLOG_TAG', value: binlog_sha1],
                [$class: 'StringParameterValue', name: 'IMPORTER_TAG', value: importer_sha1],
                [$class: 'StringParameterValue', name: 'CDC_TAG', value: cdc_sha1],
                [$class: 'StringParameterValue', name: 'BR_TAG', value: br_sha1],
                [$class: 'StringParameterValue', name: 'DUMPLING_TAG', value: dumpling_sha1],
                [$class: 'StringParameterValue', name: 'TIFLASH_TAG', value: tiflash_sha1],
                [$class: 'StringParameterValue', name: 'HOTFIX_TAG', value: RELEASE_TAG],
                [$class: 'StringParameterValue', name: 'TIUP_MIRRORS', value: TIUP_MIRRORS],
                [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: ARCH_ARM],
                [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: ARCH_X86],
                [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: ARCH_MAC],
                [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: ARCH_MAC_ARM],
                [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
        ]

}