/*
* @GIT_BRANCH(string:repo branch, Required)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
*/

properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'RELEASE_BRANCH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_BUMPVERION_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_BUMPVERSION_PRID',
                        trim: true
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD'
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'NEED_DEBUG_IMAGE'
                ),
                booleanParam(
                        defaultValue: false,
                        name: 'DEBUG_MODE'
                ),
                string(
                        defaultValue: '',
                        name: 'TIDB_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_HASH',
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
                        name: 'NG_MONITORING_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIDB_BINLOG_HASH',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TICDC_HASH',
                        trim: true
                ),
        ])
])


HARBOR_REGISTRY_PROJECT_PREFIX = 'hub.pingcap.net/qa'
if (params.DEBUG_MODE) {
    HARBOR_REGISTRY_PROJECT_PREFIX = 'hub.pingcap.net/ee-debug'
    println('DEBUG_MODE is true, use hub.pingcap.net/ee-debug')
}

// pre-release push image to harbor.pingcap.net
// release push image to ucloud and dockerhub
// version >= 6.1.0 need multi-arch image
// not need multi-arch image name example:
//    amd64: harbor.pingcap.net/qa/tidb:v5.4.0-pre
//    arm64: harbor.pingcap.net/qa/tidb-arm64:v5.4.0-pre
// version need multi-arch image name example: (user image tag to distingush multi-arch image)
//    amd64: harbor.pingcap.net/qa/tidb:v6.1.0-pre-amd64
//    arm64: harbor.pingcap.net/qa/tidb:v6.1.0-pre-arm64
//    multi-arch: harbor.pingcap.net/qa/tidb:v6.1.0-pre
def get_image_str_for_community(product, arch, tag, failpoint, if_multi_arch) {
    def imageTag = tag
    def imageName = product
    if (product == "monitoring") {
        imageName = "tidb-monitor-initializer"
    }
    if (!if_multi_arch && arch == "arm64") {
        imageName = imageName + "-arm64"
    }

    imageTag = imageTag + "-pre"
    if (if_multi_arch && !failpoint) {
        imageTag = imageTag + "-" + arch
    }
    if (failpoint) {
        imageTag = imageTag + "-" + "failpoint"
    }


    def imageStr = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${imageTag}"

    return imageStr
}

def get_sha(hash, repo, branch) {
    if (hash != "") {
        return hash
    } else {
        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
        return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
    }

}

def test_binary_already_build(binary_url) {
    cacheExisted = sh(returnStatus: true, script: """
    if curl --output /dev/null --silent --head --fail ${binary_url}; then exit 0; else exit 1; fi
    """)
    if (cacheExisted == 0) {
        return true
    } else {
        return false
    }
}


// only release version >= v6.1.0 need multi-arch image
def versionNeedMultiArch(version) {
    return true
}

NEED_MULTIARCH = versionNeedMultiArch(RELEASE_TAG)
IMAGE_TAG = RELEASE_TAG + "-pre"


def release_one(repo, arch, failpoint) {
    def actualRepo = repo
    def hash = ""
    if (repo == "br" && RELEASE_TAG >= "v5.2.0") {
        actualRepo = "tidb"
        hash = "${TIDB_HASH}"
    }
    if (repo == "dumpling" && RELEASE_TAG >= "v5.3.0") {
        actualRepo = "tidb"
        hash = "${TIDB_HASH}"
    }
    if (repo == "ticdc") {
        actualRepo = "tiflow"
        hash = "${TICDC_HASH}"
    }
    if (repo == "dm") {
        actualRepo = "tiflow"
        hash = "${TICDC_HASH}"
    }
    if (repo == "tidb-lightning") {
        hash = "${TIDB_HASH}"
    }
    if (repo == "tidb") {
        hash = "${TIDB_HASH}"
    }
    if (repo == "tikv") {
        hash = "${TIKV_HASH}"
    }
    if (repo == "pd") {
        hash = "${PD_HASH}"
    }
    if (repo == "ng-monitoring") {
        hash = "${NG_MONITORING_HASH}"
    }
    if (repo == "tidb-binlog") {
        hash = "${TIDB_BINLOG_HASH}"
    }
    if (repo == "tiflash") {
        hash = "${TIFLASH_HASH}"
    }

    def sha1 = get_sha(hash, actualRepo, RELEASE_BRANCH)
    if (TIKV_BUMPVERION_HASH.length() > 1 && repo == "tikv") {
        sha1 = TIKV_BUMPVERION_HASH
    }
    if (repo == "monitoring") {
        sha1 = get_sha(hash, actualRepo, RELEASE_BRANCH)
    }


    println "${repo}: ${sha1}"
    def binary = "builds/pingcap/${repo}/optimization/${RELEASE_TAG}/${sha1}/centos7/${repo}-linux-${arch}.tar.gz"
    if (failpoint) {
        binary = "builds/pingcap/${repo}/test/failpoint/${RELEASE_TAG}/${sha1}/linux-${arch}/${repo}.tar.gz"
    }

    def paramsBuild = [
            string(name: "ARCH", value: arch),
            string(name: "OS", value: "linux"),
            string(name: "EDITION", value: "community"),
            string(name: "OUTPUT_BINARY", value: binary),
            string(name: "REPO", value: actualRepo),
            string(name: "PRODUCT", value: repo),
            string(name: "GIT_HASH", value: sha1),
            string(name: "RELEASE_TAG", value: RELEASE_TAG),
            string(name: "TARGET_BRANCH", value: RELEASE_BRANCH),
            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
    ]
    if (repo == "tiflash") {
        paramsBuild = [
                string(name: "ARCH", value: arch),
                string(name: "OS", value: "linux"),
                string(name: "EDITION", value: "community"),
                string(name: "OUTPUT_BINARY", value: binary),
                string(name: "REPO", value: "tics"),
                string(name: "PRODUCT", value: "tics"),
                string(name: "GIT_HASH", value: sha1),
                string(name: "RELEASE_TAG", value: RELEASE_TAG),
                string(name: "TARGET_BRANCH", value: RELEASE_BRANCH),
                [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
        ]
    }
    if (failpoint) {
        paramsBuild.push([$class: 'BooleanParameterValue', name: 'FAILPOINT', value: true])
    }
    if (TIKV_BUMPVERSION_PRID.length() > 1 && repo == "tikv") {
        paramsBuild.push([$class: 'StringParameterValue', name: 'GIT_PR', value: TIKV_BUMPVERSION_PRID])
    }
    if (repo == "monitoring") {
        paramsBuild.push([$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_BRANCH])
    }
    if (test_binary_already_build("${FILE_SERVER_URL}/download/${binary}") && !params.FORCE_REBUILD) {
        echo "binary already build: ${binary}"
        echo "forece rebuild: ${params.FORCE_REBUILD}"
        echo "skip build"
    } else {
        echo "force rebuild: ${params.FORCE_REBUILD}"
        echo "binary not existed or forece_rebuild is true"
        println "start build binary ${repo} ${arch}"
        println "params: ${paramsBuild}"
        build job: "build-common",
                wait: true,
                parameters: paramsBuild
    }


    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${repo}"
    // if current version not need multi-arch image, then use image image to distingush amd64 and arm64.
    // otherwise, use imageTag to distingush different version.
    // example1:
    // hub.pingcap.net/qa/tidb:v5.4.0-pre
    // hub.pingcap.net/qa/tidb-arm64:v5.4.0-pre
    // example2:
    // hub.pingcap.net/qa/tidb:v6.1.0-pre-amd64
    // hub.pingcap.net/qa/tidb:v6.1.0-pre-arm64
    def image = get_image_str_for_community(repo, arch, RELEASE_TAG, failpoint, NEED_MULTIARCH)

    def paramsDocker = [
            string(name: "ARCH", value: arch),
            string(name: "OS", value: "linux"),
            string(name: "INPUT_BINARYS", value: binary),
            string(name: "REPO", value: repo),
            string(name: "PRODUCT", value: repo),
            string(name: "RELEASE_TAG", value: RELEASE_TAG),
            string(name: "DOCKERFILE", value: dockerfile),
            string(name: "RELEASE_DOCKER_IMAGES", value: image),
    ]
    println "start build image ${repo} ${arch}"
    println "params: ${paramsDocker}"
    build job: "docker-common",
            wait: true,
            parameters: paramsDocker


    if (NEED_DEBUG_IMAGE && arch == "amd64") {
        def dockerfileForDebug = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/debug-image/${repo}"
        def imageForDebug = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${repo}:${IMAGE_TAG}-debug"
        if (failpoint) {
            imageForDebug = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${repo}:${IMAGE_TAG}-failpoint-debug"
        }
        def paramsDockerForDebug = [
                string(name: "ARCH", value: "amd64"),
                string(name: "OS", value: "linux"),
                string(name: "INPUT_BINARYS", value: binary),
                string(name: "REPO", value: repo),
                string(name: "PRODUCT", value: repo),
                string(name: "RELEASE_TAG", value: RELEASE_TAG),
                string(name: "DOCKERFILE", value: dockerfileForDebug),
                string(name: "RELEASE_DOCKER_IMAGES", value: imageForDebug),
        ]
        if (repo in ["dumpling", "ticdc", "tidb-binlog", "tidb", "tikv", "pd"]) {
            println "start build debug image ${repo} ${arch}"
            println "params: ${paramsDockerForDebug}"
            build job: "docker-common",
                    wait: true,
                    parameters: paramsDockerForDebug
        } else {
            println "only support amd64 for debug image, only the following repo can build debug image: [dumpling,ticdc,tidb-binlog,tidb,tikv,pd]"
        }
    }

    // dm version >= v5.3.0 && < v6.0.0 need build image pingcap/dm-monitor-initializer
    if (repo == "dm" && RELEASE_TAG < "v6.0.0") {
        def dockerfileForDmMonitorInitializer = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/dm-monitor-initializer"
        def imageNameForDmMonitorInitializer = "dm-monitor-initializer"
        if (arch == "arm64") {
            imageNameForDmMonitorInitializer = imageNameForDmMonitorInitializer + "-arm64"
        }
        imageNameForDmMonitorInitializer = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageNameForDmMonitorInitializer}:${IMAGE_TAG},pingcap/${imageNameForDmMonitorInitializer}:${IMAGE_TAG}"
        if (params.DEBUG_MODE) {
            println "run pipeline in debug mode, not push dm pre-release image to dockerhub, harbor only"
            imageNameForDmMonitorInitializer = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageNameForDmMonitorInitializer}:${IMAGE_TAG}"
        }
        def paramsDockerDmMonitorInitializer = [
                string(name: "ARCH", value: arch),
                string(name: "OS", value: "linux"),
                string(name: "INPUT_BINARYS", value: binary),
                string(name: "REPO", value: "dm"),
                string(name: "PRODUCT", value: "dm_monitor_initializer"),
                string(name: "RELEASE_TAG", value: RELEASE_TAG),
                string(name: "DOCKERFILE", value: dockerfileForDmMonitorInitializer),
                string(name: "RELEASE_DOCKER_IMAGES", value: imageNameForDmMonitorInitializer),
        ]
        build job: "docker-common",
                wait: true,
                parameters: paramsDockerDmMonitorInitializer

    }

    if (repo == "br") {
        println("start push tidb-lightning")
        def dockerfileLightning = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/tidb-lightning"
        imageName = "tidb-lightning"
        if (arch == "arm64" && !NEED_MULTIARCH) {
            imageName = imageName + "-arm64"
        }
        def imageTag = IMAGE_TAG
        if (NEED_MULTIARCH) {
            imageTag = imageTag + "-" + arch
        }
        def imageLightling = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${imageTag}"
        if (params.DEBUG_MODE) {
            imageLightling = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${imageTag}"
        }
        def paramsDockerLightning = [
                string(name: "ARCH", value: arch),
                string(name: "OS", value: "linux"),
                string(name: "INPUT_BINARYS", value: binary),
                string(name: "REPO", value: "lightning"),
                string(name: "PRODUCT", value: "lightning"),
                string(name: "RELEASE_TAG", value: RELEASE_TAG),
                string(name: "DOCKERFILE", value: dockerfileLightning),
                string(name: "RELEASE_DOCKER_IMAGES", value: imageLightling),
        ]
        build job: "docker-common",
                wait: true,
                parameters: paramsDockerLightning

        if (NEED_DEBUG_IMAGE && arch == "amd64") {
            def dockerfileLightningForDebug = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/debug-image/tidb-lightning"
            def imageLightlingForDebug = "${HARBOR_REGISTRY_PROJECT_PREFIX}/tidb-lightning:${IMAGE_TAG}-debug"
            def paramsDockerLightningForDebug = [
                    string(name: "ARCH", value: "amd64"),
                    string(name: "OS", value: "linux"),
                    string(name: "INPUT_BINARYS", value: binary),
                    string(name: "REPO", value: "lightning"),
                    string(name: "PRODUCT", value: "lightning"),
                    string(name: "RELEASE_TAG", value: RELEASE_TAG),
                    string(name: "DOCKERFILE", value: dockerfileLightningForDebug),
                    string(name: "RELEASE_DOCKER_IMAGES", value: imageLightlingForDebug),
            ]
            build job: "docker-common",
                    wait: true,
                    parameters: paramsDockerLightningForDebug
        }
    }
}

def release_docker(releaseRepos, builds, arch) {
    for (item in releaseRepos) {
        def product = "${item}"
        def product_show = product
        if (product_show == "br") {
            product_show = "br && tidb-lightning"
        }
        builds["build  " + product_show + " " + arch] = {
            release_one(product, arch, false)
        }
    }

    if (arch == "amd64") {
        failpointRepos = ["tidb", "pd", "tikv"]
        for (item in failpointRepos) {
            def product = "${item}"
            builds["build ${item} failpoint" + arch] = {
                release_one(product, arch, true)
            }
        }
    }

}


def manifest_multiarch_image() {
    def imageNames = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "tidb-monitor-initializer", "tidb-lightning"]
    if (RELEASE_TAG >= "v5.3.0") {
        // build ng-monitoring only for v5.3.0+
        // build dm only for v5.3.0+
        imageNames.add("ng-monitoring")
        imageNames.add("dm")
    }
    def manifest_multiarch_builds = [:]
    for (imageName in imageNames) {
        def image = imageName
        manifest_multiarch_builds[image + " multi-arch"] = {
            def paramsManifest = [
                    string(name: "AMD64_IMAGE", value: "${HARBOR_REGISTRY_PROJECT_PREFIX}/${image}:${RELEASE_TAG}-pre-amd64"),
                    string(name: "ARM64_IMAGE", value: "${HARBOR_REGISTRY_PROJECT_PREFIX}/${image}:${RELEASE_TAG}-pre-arm64"),
                    string(name: "MULTI_ARCH_IMAGE", value: "${HARBOR_REGISTRY_PROJECT_PREFIX}/${image}:${RELEASE_TAG}-pre"),
            ]
            build job: "manifest-multiarch-common",
                    wait: true,
                    parameters: paramsManifest

            if (params.DEBUG_MODE) {
                println "run pipeline in debug mode, only push image to harbor, not push to dockerhub"
            }
        }
    }
    parallel manifest_multiarch_builds


}


stage("release") {
    node("${GO_BUILD_SLAVE}") {
        container("golang") {
            releaseRepos = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "monitoring"]
            if (RELEASE_TAG >= "v5.3.0") {
                // build ng-monitoring only for v5.3.0+
                // build dm only for v5.3.0+
                releaseRepos.add("ng-monitoring")
                releaseRepos.add("dm")
            }
            builds = [:]
            release_docker(releaseRepos, builds, "amd64")

            if (RELEASE_BRANCH >= "release-5.1") {
                release_docker(releaseRepos, builds, "arm64")
            } else if (params.DEBUG_MODE) {
                release_docker(releaseRepos, builds, "arm64")
            } else {
                println("skip build arm64 because only v5.1.x and v5.4+ support")
            }

            parallel builds

        }
    }
}

if (NEED_MULTIARCH) {
    node("${GO_BUILD_SLAVE}") {
        container("golang") {
            stage("create multi-arch image") {
                manifest_multiarch_image()
            }
            stage("sync community image to dockerhub") {
                def imageNames = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "tidb-monitor-initializer", "tidb-lightning"]
                if (RELEASE_TAG >= "v5.3.0") {
                    // build ng-monitoring only for v5.3.0+
                    // build dm only for v5.3.0+
                    imageNames.add("ng-monitoring")
                    imageNames.add("dm")
                }
                def builds = [:]
                for (imageName in imageNames) {
                    def image = imageName
                    builds[image + " sync"] = {
                        source_image = "hub.pingcap.net/qa/${image}:${RELEASE_TAG}-pre"
                        dest_image = "pingcap/${image}:${RELEASE_TAG}-pre"
                        def default_params = [
                                string(name: 'SOURCE_IMAGE', value: source_image),
                                string(name: 'TARGET_IMAGE', value: dest_image),
                        ]
                        build(job: "jenkins-image-syncer",
                                parameters: default_params,
                                wait: true)
                    }
                }
                parallel builds

            }
        }
    }
}
