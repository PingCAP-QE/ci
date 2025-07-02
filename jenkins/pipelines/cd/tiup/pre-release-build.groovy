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


properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        trim: true,
                        description: '预发布 tag, example v5.2.0'
                ),
                string(
                        defaultValue: '',
                        name: 'RELEASE_BRANCH',
                        trim: true,
                        description: '预发布分支, 构建基于这个分支最新代码, example release-5.0'
                ),
                string(
                        defaultValue: '',
                        name: 'TIDB_PRM_ISSUE',
                        trim: true,
                        description: '预发布 issue id, 当填写了 issue id 的时候，sre-bot 会自动更新各组件 hash 到 issue 上'
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_BUMPVERION_HASH',
                        trim: true,
                        description: 'tikv 的version 升级需要修改代码，所以我们通过一个pr提前bump version ，这里填写pr commit hash, pr 示例:https://github.com/tikv/tikv/pull/10406'
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_BUMPVERSION_PRID',
                        trim: true,
                        description: 'tikv 的version 升级需要修改代码，所以我们通过一个pr提前bump version ，这里填写pr id, pr 示例: https://github.com/tikv/tikv/pull/10406'
                ),
                booleanParam(
                        defaultValue: false,
                        name: 'FORCE_REBUILD',
                        description: '是否需要强制重新构建（false 则按照hash检查文件服务器上是否存在对应二进制，存在则不构建）'
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'ARCH_ARM'
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'ARCH_X86'
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'ARCH_MAC'
                ),
        ])
])


tidb_sha1=""
tikv_sha1=""
pd_sha1=""
tiflash_sha1=""
br_sha1=""
binlog_sha1=""
lightning_sha1=""
tools_sha1=""
cdc_sha1=""
dm_sha1=""
dumpling_sha1=""
ng_monitoring_sha1=""
tidb_ctl_githash=""
enterprise_plugin_sha1=""

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
    println "enterprise_plugin_sha1: ${enterprise_plugin_sha1}"
    withCredentials([string(credentialsId: 'token-update-prm-issue', variable: 'GITHUB_TOKEN')]) {
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
            echo 'dm = "${dm_sha1}"' >> githash.toml
            echo 'tidb_ctl = "${tidb_ctl_githash}"' >> githash.toml
            echo 'binlog = "${binlog_sha1}"' >> githash.toml
            echo 'ng_monitoring = "${ng_monitoring_sha1}"' >> githash.toml
            echo 'enterprise_plugin_sha1 = "${enterprise_plugin_sha1}"' >> githash.toml

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
    def namespace = "jenkins-tidb"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
    def jnlp_docker_image = "jenkins/inbound-agent:3148.v532a_7e715ee3-10"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '8000m', resourceRequestMemory: '12Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],

                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

run_with_pod {
    container("golang") {
        stage("get hash ${RELEASE_BRANCH} from github") {
            get_sha()
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
    }
}
