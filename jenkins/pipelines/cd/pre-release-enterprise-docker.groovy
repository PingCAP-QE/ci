/*
* @TIDB_TAG
* @TIKV_TAG
* @PD_TAG
* @BINLOG_TAG
* @TIFLASH_TAG
* @LIGHTNING_TAG
* @TOOLS_TAG
* @BR_TAG
* @CDC_TAG
* @RELEASE_TAG
*/
properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'RELEASE_BRANCH',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIDB_HASH',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_HASH',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PD_HASH',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIFLASH_HASH',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PLUGIN_HASH',
                        description: '',
                        trim: true
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD',
                        description: ''
                ),
                booleanParam(
                        defaultValue: false,
                        name: 'DEBUG_MODE'
                )
        ])
])

HARBOR_REGISTRY_PROJECT_PREFIX = 'hub.pingcap.net/qa'
if (params.DEBUG_MODE) {
    HARBOR_REGISTRY_PROJECT_PREFIX = 'hub.pingcap.net/ee-debug'
    println('DEBUG_MODE is true, use hub.pingcap.net/ee-debug')
}

label = "${JOB_NAME}-${BUILD_NUMBER}"

def run_with_pod(Closure body) {
    def cloud = "kubernetes"
    def namespace = "jenkins-cd"
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
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
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

def libs
def taskStartTimeInMillis = System.currentTimeMillis()

// only release version >= v6.1.0 need multi-arch image
def versionNeedMultiArch(version) {
    return true
}

NEED_MULTIARCH = versionNeedMultiArch(RELEASE_TAG)

try {
    catchError {
        stage('Prepare') {
            node('delivery') {
                container('delivery') {
                    dir('centos7') {
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                        checkout scm
                        libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
                    }
                }
            }
        }

        stage("enterprise docker image amd64 build") {
            node("delivery") {
                container("delivery") {
                    def arch_amd64 = "amd64"
                    libs.parallel_enterprise_docker(arch_amd64, false, NEED_MULTIARCH)
                }
            }
        }

        stage("enterprise docker image arm64 build") {
            node("arm") {
                def arch_arm64 = "arm64"
                libs.parallel_enterprise_docker(arch_arm64, false, NEED_MULTIARCH)
            }
        }

        if (NEED_MULTIARCH) {
            stage("manifest multi-arch docker image build") {
                node("delivery") {
                    container("delivery") {
                        def source = "hub.pingcap.net/qa/"
                        def type = "rc"
                        libs.parallel_enterprise_docker_multiarch(false)
                        libs.enterprise_docker_sync_gcr(source, type)
                    }
                }
            }
        }
        currentBuild.result = "SUCCESS"
    }

} catch (Exception e) {
    currentBuild.result = "FAILURE"
} finally {
    build job: 'send_notify',
            wait: true,
            parameters: [
                    [$class: 'StringParameterValue', name: 'RESULT_JOB_NAME', value: "${JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'RESULT_BUILD_RESULT', value: currentBuild.result],
                    [$class: 'StringParameterValue', name: 'RESULT_BUILD_NUMBER', value: "${BUILD_NUMBER}"],
                    [$class: 'StringParameterValue', name: 'RESULT_RUN_DISPLAY_URL', value: "${RUN_DISPLAY_URL}"],
                    [$class: 'StringParameterValue', name: 'RESULT_TASK_START_TS', value: "${taskStartTimeInMillis}"],
                    [$class: 'StringParameterValue', name: 'SEND_TYPE', value: "ALL"]

            ]
}
