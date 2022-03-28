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
tools_sha1=""
cdc_sha1=""
dumpling_sha1=""
ng_monitoring_sha1=""
tidb_ctl_githash=""

def OS_LINUX = "linux"
def OS_DARWIN = "darwin"
def ARM64 = "arm64"
def AMD64 = "amd64"
def PLATFORM_CENTOS = "centos7"
def PLATFORM_DARWIN = "darwin"
def PLATFORM_DARWINARM = "darwin-arm64"

def get_sha() {
    if ( TIDB_PRM_ISSUE != "") {
        println "tidb_prm issue ${TIDB_PRM_ISSUE}  --> ${RELEASE_TAG}"
        sh """
        if [ -f githash.toml ]; then
            rm -f githash.toml
        fi
        echo "[tidbPrmIssue]" >> githash.toml
        echo 'issue_id = "${TIDB_PRM_ISSUE}"' >> githash.toml
        echo "[commitHash]" >> githash.toml
        """
    }

    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    if (RELEASE_TAG >= "v5.2.0") {
        br_sha1 = tidb_sha1
    } else {
        br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    }
    if (RELEASE_TAG >= "v5.3.0") {
        dumpling_sha1 = tidb_sha1
        ng_monitoring_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ng-monitoring -source=github -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    } else {
        dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    }
    binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    lightning_sha1 = br_sha1
    
    if (RELEASE_TAG >= "v5.3.0") {
        tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=master -s=${FILE_SERVER_URL}").trim()
    } else {
        tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    }

    cdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ticdc -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
    tidb_ctl_githash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -source=github -version=master -s=${FILE_SERVER_URL}").trim()

    if (TIKV_BUMPVERION_HASH.length() == 40) {
        return tikv_sha1 = TIKV_BUMPVERION_HASH
    }

    println "tidb_sha1: ${tidb_sha1}"
    println "br_sha1: ${br_sha1}"
    println "lightning_sha1: ${lightning_sha1}"
    println "dumpling_sha1: ${dumpling_sha1}"
    println "tikv_sha1: ${tikv_sha1}"
    println "pd_sha1: ${pd_sha1}"
    println "tiflash_sha1: ${tiflash_sha1}"
    println "tools_sha1: ${tools_sha1}"
    println "cdc_sha1: ${cdc_sha1}"
    println "tidb_ctl_hash: ${tidb_ctl_githash}"
    println "binlog_sha1: ${binlog_sha1}"
    println "ng_monitoring_sha1: ${ng_monitoring_sha1}"
    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'GITHUB_TOKEN')]) { 
        if ( TIDB_PRM_ISSUE != "") {
            sh """
            echo 'tidb = "${tidb_sha1}"' >> githash.toml
            echo 'br = "${br_sha1}"' >> githash.toml
            echo 'lightning = "${lightning_sha1}"' >> githash.toml
            echo 'dumpling = "${dumpling_sha1}"' >> githash.toml
            echo 'tikv = "${tikv_sha1}"' >> githash.toml
            echo 'pd = "${pd_sha1}"' >> githash.toml
            echo 'tiflash = "${tiflash_sha1}"' >> githash.toml
            echo 'tools = "${tools_sha1}"' >> githash.toml
            echo 'cdc = "${cdc_sha1}"' >> githash.toml
            echo 'tidb_ctl = "${tidb_ctl_githash}"' >> githash.toml
            echo 'binlog = "${binlog_sha1}"' >> githash.toml
            echo 'ng_monitoring = "${ng_monitoring_sha1}"' >> githash.toml

            cat githash.toml
            curl -O ${FILE_SERVER_URL}/download/cicd/tools/update-prm-issue
            chmod +x update-prm-issue
            ./update-prm-issue
            """
        }
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
            build job: "optimization-build-tidb",
                    wait: true,
                    parameters: [
                            [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                            [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                            [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                            [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: binlog_sha1],
                            [$class: 'StringParameterValue', name: 'LIGHTNING_HASH', value: lightning_sha1],
                            [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tools_sha1],
                            [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                            [$class: 'StringParameterValue', name: 'BR_HASH', value: br_sha1],
                            [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: dumpling_sha1],
                            [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                            [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
                            [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                            [$class: 'BooleanParameterValue', name: 'SKIP_TIFLASH', value: false],
                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                            [$class: 'StringParameterValue', name: 'TIKV_PRID', value: TIKV_BUMPVERSION_PRID],
                            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                            [$class: 'StringParameterValue', name: 'OS', value: OS_LINUX],
                            [$class: 'StringParameterValue', name: 'ARCH', value: ARM64],
                            [$class: 'StringParameterValue', name: 'PLATFORM', value: PLATFORM_CENTOS],
                    ]
        }
    }
    if (params.ARCH_MAC) {
        builds["Build on darwin/amd64"] = {
            build job: "optimization-build-tidb",
                    wait: true,
                    parameters: [
                            [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                            [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                            [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                            [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: binlog_sha1],
                            [$class: 'StringParameterValue', name: 'LIGHTNING_HASH', value: lightning_sha1],
                            [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tools_sha1],
                            [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                            [$class: 'StringParameterValue', name: 'BR_HASH', value: br_sha1],
                            [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: dumpling_sha1],
                            [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                            [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
                            [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                            [$class: 'BooleanParameterValue', name: 'SKIP_TIFLASH', value: false],
                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                            [$class: 'StringParameterValue', name: 'TIKV_PRID', value: TIKV_BUMPVERSION_PRID],
                            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                            [$class: 'StringParameterValue', name: 'OS', value: OS_DARWIN],
                            [$class: 'StringParameterValue', name: 'ARCH', value: AMD64],
                            [$class: 'StringParameterValue', name: 'PLATFORM', value: PLATFORM_DARWIN],
                    ]
        }
    }

    if (params.ARCH_X86) {
        builds["Build on linux/amd64"] = {
            build job: "optimization-build-tidb",
                    wait: true,
                    parameters: [
                            [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                            [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                            [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                            [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: binlog_sha1],
                            [$class: 'StringParameterValue', name: 'LIGHTNING_HASH', value: lightning_sha1],
                            [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tools_sha1],
                            [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                            [$class: 'StringParameterValue', name: 'BR_HASH', value: br_sha1],
                            [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: dumpling_sha1],
                            [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                            [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
                            [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                            [$class: 'BooleanParameterValue', name: 'SKIP_TIFLASH', value: false],
                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                            [$class: 'StringParameterValue', name: 'TIKV_PRID', value: TIKV_BUMPVERSION_PRID],
                            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                            [$class: 'StringParameterValue', name: 'OS', value: OS_LINUX],
                            [$class: 'StringParameterValue', name: 'ARCH', value: AMD64],
                            [$class: 'StringParameterValue', name: 'PLATFORM', value: PLATFORM_CENTOS],    
                    ]
        }
    }

    if (params.ARCH_MAC_ARM) {
        builds["Build on darwin/arm64"] = {
            build job: "optimization-build-tidb",
                    wait: true,
                    parameters: [
                            [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                            [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                            [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                            [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: binlog_sha1],
                            [$class: 'StringParameterValue', name: 'LIGHTNING_HASH', value: lightning_sha1],
                            [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tools_sha1],
                            [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                            [$class: 'StringParameterValue', name: 'BR_HASH', value: br_sha1],
                            [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: dumpling_sha1],
                            [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                            [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
                            [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                            [$class: 'BooleanParameterValue', name: 'SKIP_TIFLASH', value: false],
                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                            [$class: 'StringParameterValue', name: 'TIKV_PRID', value: TIKV_BUMPVERSION_PRID],
                            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                            [$class: 'StringParameterValue', name: 'OS', value: OS_DARWIN],
                            [$class: 'StringParameterValue', name: 'ARCH', value: ARM64],
                            [$class: 'StringParameterValue', name: 'PLATFORM', value: PLATFORM_DARWINARM],
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
                [$class: 'StringParameterValue', name: 'CDC_TAG', value: cdc_sha1],
                [$class: 'StringParameterValue', name: 'BR_TAG', value: br_sha1],
                [$class: 'StringParameterValue', name: 'DUMPLING_TAG', value: dumpling_sha1],
                [$class: 'StringParameterValue', name: 'TIFLASH_TAG', value: tiflash_sha1],
                [$class: 'StringParameterValue', name: 'HOTFIX_TAG', value: RELEASE_TAG],
                [$class: 'StringParameterValue', name: 'TIDB_CTL_TAG', value: tidb_ctl_githash],
                [$class: 'StringParameterValue', name: 'TIUP_MIRRORS', value: TIUP_MIRRORS],
                [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: ARCH_ARM],
                [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: ARCH_X86],
                [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: ARCH_MAC],
                [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: ARCH_MAC_ARM],
                [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
        ]

}