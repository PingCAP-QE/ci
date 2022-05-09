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
                        defaultValue: false,
                        name: 'FORCE_REBUILD',
                        description: ''
                ),
        ])
])


os = "linux"
platform = "centos7"

//def pre_build_image(product, sha1, arch) {
//    def binary = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${sha1}/${platform}/${product}-${os}-${arch}-enterprise.tar.gz"
//    if (product == "tidb-lightning") {
//        binary = "builds/pingcap/br/optimization/${RELEASE_TAG}/${sha1}/${platform}/br-${os}-${arch}-enterprise.tar.gz"
//    }
//    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${product}"
//    def imageName = product
//    def repo = product
//    if (repo == "monitoring") {
//        imageName = "tidb-monitor-initializer"
//    }
//    imageName = imageName + "-enterprise"
//    if (arch == "arm64") {
//        imageName = imageName + "-arm64"
//    }
//    def image = "hub.pingcap.net/qa/${imageName}:${RELEASE_TAG}-pre"
//
//    def paramsDocker = [
//            string(name: "ARCH", value: arch),
//            string(name: "OS", value: "linux"),
//            string(name: "INPUT_BINARYS", value: binary),
//            string(name: "REPO", value: repo),
//            string(name: "PRODUCT", value: repo),
//            string(name: "RELEASE_TAG", value: RELEASE_TAG),
//            string(name: "DOCKERFILE", value: dockerfile),
//            string(name: "RELEASE_DOCKER_IMAGES", value: image),
//            string(name: "GIT_BRANCH", value: RELEASE_BRANCH),
//    ]
//    println "pre build enterprise image: ${paramsDocker}"
//    build job: "docker-common-check",
//            wait: true,
//            parameters: paramsDocker
//}

//def pre_build_tidb_image(product, sha1, plugin_hash, arch) {
//    // build tidb enterprise image with plugin
//    binary = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${sha1}/${platform}/${product}-${os}-${arch}-enterprise.tar.gz"
//    plugin_binary = "builds/pingcap/enterprise-plugin/optimization/${RELEASE_TAG}/${plugin_hash}/${platform}/enterprise-plugin-${os}-${arch}-enterprise.tar.gz"
//
//    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${product}"
//    if (product == "tidb" && os == "linux" && arch == "amd64") {
//        dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/enterprise/tidb"
//    }
//    if (product == "tidb" && os == "linux" && arch == "arm64") {
//        dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-arm64/enterprise/tidb"
//    }
//    def imageName = product
//    def repo = product
//    if (arch == "amd64") {
//        imageName = imageName + "-enterprise"
//    } else {
//        imageName = imageName + "-enterprise-arm64"
//    }
//
//    image = "hub.pingcap.net/qa/${imageName}:${RELEASE_TAG}-pre"
//    def paramsDocker = [
//            string(name: "ARCH", value: arch),
//            string(name: "OS", value: "linux"),
//            string(name: "INPUT_BINARYS", value: "${binary},${plugin_binary}"),
//            string(name: "REPO", value: repo),
//            string(name: "PRODUCT", value: repo),
//            string(name: "RELEASE_TAG", value: RELEASE_TAG),
//            string(name: "DOCKERFILE", value: dockerfile),
//            string(name: "RELEASE_DOCKER_IMAGES", value: image),
//            string(name: "GIT_BRANCH", value: RELEASE_BRANCH),
//    ]
//    println "pre build tidb enterprise image: ${paramsDocker}"
//    build job: "docker-common-check",
//            wait: true,
//            parameters: paramsDocker
//}

//def retag_enterprise_image(product, arch) {
//    def community_image_for_pre_replease = "hub.pingcap.net/qa/${product}:${RELEASE_TAG}-pre"
//    def enterprise_image_for_pre_replease = "hub.pingcap.net/qa/${product}-enterprise:${RELEASE_TAG}-pre"
//    if (arch == 'arm64') {
//        community_image_for_pre_replease = "hub.pingcap.net/qa/${product}-arm64:${RELEASE_TAG}-pre"
//        enterprise_image_for_pre_replease = "hub.pingcap.net/qa/${product}-enterprise-arm64:${RELEASE_TAG}-pre"
//    }
//
//    def default_params = [
//            string(name: 'SOURCE_IMAGE', value: community_image_for_pre_replease),
//            string(name: 'TARGET_IMAGE', value: enterprise_image_for_pre_replease),
//    ]
//    println "retag enterprise image from community image: ${default_params}"
//    build(job: "jenkins-image-syncer",
//            parameters: default_params,
//            wait: true)
//
//}

label = "${JOB_NAME}-${BUILD_NUMBER}"

def run_with_pod(Closure body) {
    def cloud = "kubernetes"
    def namespace = "jenkins-cd"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
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

try {
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
        run_with_pod {
            container("golang") {
                def arch_amd64 = "amd64"
                libs.parallel_enterprise_docker(libs, arch_amd64, false)
            }
        }
    }

    stage("enterprise docker image arm64 build") {
        node("arm") {
            def arch_arm64 = "arm64"
            libs.parallel_enterprise_docker(libs, arch_arm64, false)
        }
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
                    [$class: 'StringParameterValue', name: 'RESULT_TASK_START_TS', value: "${taskStartTimeInMillis}"]
            ]
}

//private void docker_product(arch) {
//    def builds = [:]
//    builds["tidb image"] = {
//        pre_build_tidb_image("tidb", TIDB_HASH, PLUGIN_HASH, arch)
//    }
//    builds["tikv image"] = {
//        pre_build_image("tikv", TIKV_HASH, arch)
//    }
//    builds["tiflash image"] = {
//        pre_build_image("tiflash", TIFLASH_HASH, arch)
//    }
//    builds["pd image"] = {
//        pre_build_image("pd", PD_HASH, arch)
//    }
//
//    builds["tidb-lightning image"] = {
//        retag_enterprise_image("tidb-lightning", arch)
//    }
//    builds["dm"] = {
//        retag_enterprise_image("dm", arch)
//    }
//    builds["tidb-binlog image"] = {
//        retag_enterprise_image("tidb-binlog", arch)
//    }
//    builds["ticdc image"] = {
//        retag_enterprise_image("ticdc", arch)
//    }
//    builds["br image"] = {
//        retag_enterprise_image("br", arch)
//    }
//    builds["dumpling image"] = {
//        retag_enterprise_image("dumpling", arch)
//    }
//    builds["ng-monitoring image"] = {
//        retag_enterprise_image("ng-monitoring", arch)
//    }
//
//    parallel builds
//}

