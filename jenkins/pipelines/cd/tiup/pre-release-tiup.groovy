/*
* @ARCH_ARM
* @ARCH_X86
* @ARCH_MAC
* @ARCH_MAC_ARM

* @FORCE_REBUILD 是否需要强制重新构建（false 则按照hash检查文件服务器上是否存在对应二进制，存在则不构建）
* @RELEASE_BRANCH 预发布分支，所有构建代码基于这个分支拉取
* @RELEASE_TAG
* @TIDB_PRM_ISSUE 默认为空，当填写了 issue id 的时候，sre-bot 会自动更新各组件 hash 到 issue 上

* @TIUP_MIRRORS
* @TIKV_BUMPVERION_HASH
* @TIKV_BUMPVERSION_PRID
*/


tidb_sha1 = ""
tikv_sha1 = ""
pd_sha1 = ""
tiflash_sha1 = ""
br_sha1 = ""
binlog_sha1 = ""
lightning_sha1 = ""
tools_sha1 = ""
cdc_sha1 = ""
dm_sha1 = ""
dumpling_sha1 = ""
ng_monitoring_sha1 = ""
tidb_ctl_githash = ""
tidb_monitor_initializer_sha1 = ""
enterprise_plugin_sha1 = ""
tools_sha1 = ""

def OS_LINUX = "linux"
def OS_DARWIN = "darwin"
def ARM64 = "arm64"
def AMD64 = "amd64"
def PLATFORM_CENTOS = "centos7"
def PLATFORM_DARWIN = "darwin"
def PLATFORM_DARWINARM = "darwin-arm64"
begin_time = new Date().format('yyyy-MM-dd HH:mm:ss')
taskStartTimeInMillis = System.currentTimeMillis()
taskFinishTimeInMillis = System.currentTimeMillis()

def get_sha() {
    if (TIDB_PRM_ISSUE != "") {
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

    sh "cp /gethash.py gethash.py"
    tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_BRANCH}").trim()
    enterprise_plugin_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=enterprise-plugin -version=${RELEASE_BRANCH}").trim()
    tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${RELEASE_BRANCH}").trim()
    pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${RELEASE_BRANCH} ").trim()
    tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${RELEASE_BRANCH} ").trim()
    if (RELEASE_TAG >= "v5.2.0") {
        br_sha1 = tidb_sha1
    } else {
        br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${RELEASE_BRANCH} ").trim()
    }
    if (RELEASE_TAG >= "v5.3.0") {
        dumpling_sha1 = tidb_sha1
        ng_monitoring_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ng-monitoring -source=github -version=${RELEASE_BRANCH} ").trim()
    } else {
        dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${RELEASE_BRANCH} ").trim()
    }
    binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${RELEASE_BRANCH} ").trim()
    lightning_sha1 = br_sha1

    if (RELEASE_TAG >= "v5.3.0") {
        tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=master ").trim()
    } else {
        tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${RELEASE_BRANCH} ").trim()
    }

    cdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ticdc -version=${RELEASE_BRANCH} ").trim()
    if (RELEASE_TAG >= "v5.3.0") {
        dm_sha1 = cdc_sha1
    } else {
        println "dm is not supported in ${RELEASE_TAG}, support dm release from v5.3.0(include v5.3.0)"
    }
    tidb_ctl_githash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -source=github -version=master ").trim()

    if (TIKV_BUMPVERION_HASH.length() == 40) {
        tikv_sha1 = TIKV_BUMPVERION_HASH
    }
    tidb_monitor_initializer_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=monitoring -version=master").trim()

    println "tidb_sha1: ${tidb_sha1}"
    println "br_sha1: ${br_sha1}"
    println "lightning_sha1: ${lightning_sha1}"
    println "dumpling_sha1: ${dumpling_sha1}"
    println "tikv_sha1: ${tikv_sha1}"
    println "pd_sha1: ${pd_sha1}"
    println "tiflash_sha1: ${tiflash_sha1}"
    println "tools_sha1: ${tools_sha1}"
    println "cdc_sha1: ${cdc_sha1}"
    println "dm_sha1: ${dm_sha1}"
    println "tidb_ctl_hash: ${tidb_ctl_githash}"
    println "binlog_sha1: ${binlog_sha1}"
    println "ng_monitoring_sha1: ${ng_monitoring_sha1}"
    println "tidb_monitor_initializer_sha1: ${tidb_monitor_initializer_sha1}"
    println "enterprise_plugin_sha1: ${enterprise_plugin_sha1}"
    withCredentials([string(credentialsId: 'token-update-prm-issue', variable: 'GITHUB_TOKEN')]) {
        if (TIDB_PRM_ISSUE != "") {
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
            echo 'dm = "${dm_sha1}"' >> githash.toml
            echo 'tidb_ctl = "${tidb_ctl_githash}"' >> githash.toml
            echo 'binlog = "${binlog_sha1}"' >> githash.toml
            echo 'ng_monitoring = "${ng_monitoring_sha1}"' >> githash.toml
            echo 'enterprise_plugin = "${enterprise_plugin_sha1}"' >> githash.toml

            cat githash.toml
            curl -O ${FILE_SERVER_URL}/download/cicd/tools/update-prm-issue
            chmod +x update-prm-issue
            ./update-prm-issue
            """
        }
    }

}

label = "${JOB_NAME}-${BUILD_NUMBER}"

def run_with_pod(Closure body) {
    def cloud = "kubernetes"
    def namespace = "jenkins-cd"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/gethash:latest'
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'gethash', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '1000m', resourceRequestMemory: '2Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],

                    )
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} sh"
            body()
        }
    }
}

try {
    run_with_pod {
        container("gethash") {
            stage("get hash ${RELEASE_BRANCH} from github") {
                withCredentials([string(credentialsId: 'github-token-gethash', variable: 'GHTOKEN')]) {
                    get_sha()
                }
            }
            stage('Build') {
                builds = [:]
                if (params.ARCH_ARM) {
                    builds["Build linux/arm64"] = {
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
                                        [$class: 'StringParameterValue', name: 'DM_HASH', value: dm_sha1],
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
                    builds["Build darwin/amd64"] = {
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
                                        [$class: 'StringParameterValue', name: 'DM_HASH', value: dm_sha1],
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
                    builds["Build linux/amd64"] = {
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
                                        [$class: 'StringParameterValue', name: 'DM_HASH', value: dm_sha1],
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
                                        [$class: 'BooleanParameterValue', name: 'PRE_RELEASE', value: true],
                                ]
                    }
                }
                if (params.ARCH_MAC_ARM) {
                    builds["Build darwin/arm64"] = {
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
                                        [$class: 'StringParameterValue', name: 'DM_HASH', value: dm_sha1],
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

                builds["build enterprise binary"] = {
                    build job: "build-linux-enterprise",
                            wait: true,
                            parameters: [
                                    [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                    [$class: 'StringParameterValue', name: 'TIKV_PRID', value: TIKV_BUMPVERSION_PRID],
                                    [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                    [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                    [$class: 'StringParameterValue', name: 'ENTERPRISE_PLUGIN_HASH', value: enterprise_plugin_sha1],
                                    [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                    [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                            ]
                }
                parallel builds
            }


            stage("publish tiup staging & publish community image") {
                def publishs = [:]
                publishs["publish tiup staging"] = {
                    build job: "tiup-mirror-online-rc",
                            wait: true,
                            parameters: [
                                    [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                    [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                    [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: binlog_sha1],
                                    [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                                    [$class: 'StringParameterValue', name: 'DM_HASH', value: dm_sha1],
                                    [$class: 'StringParameterValue', name: 'BR_HASH', value: br_sha1],
                                    [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: dumpling_sha1],
                                    [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                    [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                    [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                                    [$class: 'StringParameterValue', name: 'TIUP_MIRRORS', value: TIUP_MIRRORS],
                                    [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: ARCH_ARM],
                                    [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: ARCH_X86],
                                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: ARCH_MAC],
                                    [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: ARCH_MAC_ARM],
                                    [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                                    [$class: 'StringParameterValue', name: 'TIUP_ENV', value: "staging"],
                            ]
                }
				if (!(RELEASE_TAG >= "v6.6.0")){
                publishs["publish community image"] = {
                    build job: "pre-release-docker",
                            wait: true,
                            parameters: [
                                    [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                                    [$class: 'StringParameterValue', name: 'TIKV_BUMPVERION_HASH', value: TIKV_BUMPVERION_HASH],
                                    [$class: 'StringParameterValue', name: 'TIKV_BUMPVERSION_PRID', value: TIKV_BUMPVERSION_PRID],
                                    [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                    [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                    [$class: 'BooleanParameterValue', name: 'NEED_DEBUG_IMAGE', value: true],
                                    [$class: 'BooleanParameterValue', name: 'DEBUG_MODE', value: false],
                                    [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                    [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                    [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                    [$class: 'StringParameterValue', name: 'NG_MONITORING_HASH', value: ng_monitoring_sha1],
                                    [$class: 'StringParameterValue', name: 'TIDB_BINLOG_HASH', value: binlog_sha1],
                                    [$class: 'StringParameterValue', name: 'TICDC_HASH', value: cdc_sha1],
                            ]
                }
                }
				if (RELEASE_TAG >= "v6.5.0"){
                    publishs["build community image rocky"] = {
                        build job: "pre-release-community-docker-rocky",
                                wait: true,
                                parameters: [
                                        [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                                        [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                        [$class: 'BooleanParameterValue', name: 'NEED_DEBUG_IMAGE', value: false],
                                        [$class: 'BooleanParameterValue', name: 'DEBUG_MODE', value: false],
                                        [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                        [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                        [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                        [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                        [$class: 'StringParameterValue', name: 'NG_MONITORING_HASH', value: ng_monitoring_sha1],
                                        [$class: 'StringParameterValue', name: 'TIDB_BINLOG_HASH', value: binlog_sha1],
                                        [$class: 'StringParameterValue', name: 'TICDC_HASH', value: cdc_sha1],
                                ]
                    }
				}

                parallel publishs
            }

            stage("publish enterprise & sync images") {
				def builds =[:]
				if (!(RELEASE_TAG >= "v6.6.0")){
				builds["publish enterprise image"] = {
                    build job: "pre-release-enterprise-docker",
                        wait: true,
                        parameters: [
                                [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                                [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                [$class: 'StringParameterValue', name: 'PLUGIN_HASH', value: enterprise_plugin_sha1],
                                [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                [$class: 'BooleanParameterValue', name: 'DEBUG_MODE', value: false],
                        ]
				}
                }
				if (RELEASE_TAG >= "v6.5.0"){
                    builds["build enterprise image rocky"] = {
                        build job: "pre-release-enterprise-docker-rocky",
                            wait: true,
                            parameters: [
                                    [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                                    [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                    [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                    [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                    [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                    [$class: 'StringParameterValue', name: 'PLUGIN_HASH', value: enterprise_plugin_sha1],
                                    [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                    [$class: 'BooleanParameterValue', name: 'DEBUG_MODE', value: false],
                            ]
                    }
                    builds["build tidb-dashboard"] = {
                        build job: "build-tidb-dashboard",
                            wait: true,
                            parameters: [
                                    [$class: 'StringParameterValue', name: 'GitRef', value: RELEASE_BRANCH],
                                    [$class: 'StringParameterValue', name: 'ReleaseTag', value: RELEASE_TAG],
                            ]
                    }
                }
                parallel builds
                if (RELEASE_TAG >= "v6.6.0"){
                        build job: "pre-release-docker-rocky-sync",
                            wait: true,
                            parameters: [
                                    [$class: 'StringParameterValue', name: 'Version', value: RELEASE_TAG],
                            ]
                }
            }
            stage("check artifact"){
                build job: "pre-release-check",
                        wait: true,
                        parameters: [
                                string(value: RELEASE_TAG,name: 'RELEASE_TAG'),
                                string(value: tidb_sha1,name: 'TIDB_VERSION'),
                                string(value: tikv_sha1,name: 'TIKV_VERSION'),
                                string(value: pd_sha1,name: 'PD_VERSION'),
                                string(value: tiflash_sha1,name: 'TIFLASH_VERSION'),
                                string(value: br_sha1,name: 'BR_VERSION'),
                                string(value: binlog_sha1,name: 'BINLOG_VERSION'),
                                string(value: lightning_sha1, name: 'LIGHTNING_VERSION'),
                                string(value: tools_sha1,name: 'TOOLS_VERSION'),
                                string(value: cdc_sha1,name: 'CDC_VERSION'),
                                string(value: dumpling_sha1,name: 'DUMPLING_VERSION'),
                                string(value: dm_sha1,name: 'DM_VERSION'),
                        ]
            }
        }
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    println "${e}"
    currentBuild.result = "FAILURE"
} finally {
    upload_result_to_db()
    upload_pipeline_run_data()
}

def upload_result_to_db() {
    pipeline_build_id= params.PIPELINE_BUILD_ID
    pipeline_id= "10"
    pipeline_name= "RC-Build"
    status= currentBuild.result
    build_number= BUILD_NUMBER
    job_name= JOB_NAME
    artifact_meta= "tidb commit:" + tidb_sha1 + ",tikv commit:" + tikv_sha1 + ",tiflash commit:" + tiflash_sha1+ ",dumpling commit:" + dumpling_sha1+ ",pd commit:" + pd_sha1 + ",tidb-binlog commit:" + binlog_sha1 +",ticdc commit:" + cdc_sha1 + ",dm commit:" + dm_sha1 + ",br commit:" + tidb_sha1 + ",lightning commit:" + tidb_sha1 + ",tidb-monitor-initializer commit:" + tidb_monitor_initializer_sha1 + ",ng-monitoring commit:" + ng_monitoring_sha1+",enterprise-plugin commit:"+enterprise_plugin_sha1 + ",tidb-tools commit:" + tools_sha1
    begin_time= begin_time
    end_time= new Date().format('yyyy-MM-dd HH:mm:ss')
    triggered_by= "sre-bot"
    component= "All"
    arch= "All"
    artifact_type= "All"
    branch= RELEASE_BRANCH
    version= RELEASE_TAG
    build_type= "rc-build"
    push_gcr = "Yes"

    build job: 'upload_result_to_db',
            wait: true,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_BUILD_ID', value: pipeline_build_id],
                    [$class: 'StringParameterValue', name: 'PIPELINE_ID', value: pipeline_id],
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value:  pipeline_name],
                    [$class: 'StringParameterValue', name: 'STATUS', value:  status],
                    [$class: 'StringParameterValue', name: 'BUILD_NUMBER', value:  build_number],
                    [$class: 'StringParameterValue', name: 'JOB_NAME', value:  job_name],
                    [$class: 'StringParameterValue', name: 'ARTIFACT_META', value: artifact_meta],
                    [$class: 'StringParameterValue', name: 'BEGIN_TIME', value: begin_time],
                    [$class: 'StringParameterValue', name: 'END_TIME', value:  end_time],
                    [$class: 'StringParameterValue', name: 'TRIGGERED_BY', value:  triggered_by],
                    [$class: 'StringParameterValue', name: 'COMPONENT', value: component],
                    [$class: 'StringParameterValue', name: 'ARCH', value:  arch],
                    [$class: 'StringParameterValue', name: 'ARTIFACT_TYPE', value:  artifact_type],
                    [$class: 'StringParameterValue', name: 'BRANCH', value: branch],
                    [$class: 'StringParameterValue', name: 'VERSION', value: version],
                    [$class: 'StringParameterValue', name: 'BUILD_TYPE', value: build_type],
                    [$class: 'StringParameterValue', name: 'PUSH_GCR', value: push_gcr]
            ]

}

def upload_pipeline_run_data() {
    stage("Upload pipeline run data") {
        taskFinishTimeInMillis = System.currentTimeMillis()
        build job: 'upload-pipeline-run-data-to-db',
            wait: false,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_TYPE', value: "rc build"],
                    [$class: 'StringParameterValue', name: 'STATUS', value: currentBuild.result],
                    [$class: 'StringParameterValue', name: 'JENKINS_BUILD_ID', value: "${BUILD_NUMBER}"],
                    [$class: 'StringParameterValue', name: 'JENKINS_RUN_URL', value: "${env.RUN_DISPLAY_URL}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_REVOKER', value: "sre-bot"],
                    [$class: 'StringParameterValue', name: 'ERROR_CODE', value: "0"],
                    [$class: 'StringParameterValue', name: 'ERROR_SUMMARY', value: ""],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_START_TIME', value: "${taskStartTimeInMillis}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_END_TIME', value: "${taskFinishTimeInMillis}"],
            ]
    }
}
