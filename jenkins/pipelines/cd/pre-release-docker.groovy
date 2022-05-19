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
                        defaultValue: true,
                        name: 'DEBUG_MODE'
                )
        ])
])


HARBOR_REGISTRY_PROJECT_PREFIX = "hub.pingcap.net/qa"
// DOCKERHUB_REGISTRY_PROJECT_PREFIX = "pingcap"
if (params.DEBUG_MODE) {
    println "run pipeline in debug mode"
    HARBOR_REGISTRY_PROJECT_PREFIX = "hub.pingcap.net/ee-debug"
}


def get_sha(repo, branch) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
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
    if (version.startsWith("v") && version >= "v6.1.0") {
        return true
    }
    return false
}

NEED_MULTIARCH = versionNeedMultiArch(RELEASE_TAG)
IMAGE_TAG = RELEASE_TAG + "-pre"


def release_one(repo, arch, failpoint) {
    def actualRepo = repo
    if (repo == "br" && RELEASE_TAG >= "v5.2.0") {
        actualRepo = "tidb"
    }
    if (repo == "dumpling" && RELEASE_TAG >= "v5.3.0") {
        actualRepo = "tidb"
    }
    if (repo == "ticdc") {
        actualRepo = "tiflow"
    }
    if (repo == "dm") {
        actualRepo = "tiflow"
    }
    def sha1 = get_sha(actualRepo, RELEASE_BRANCH)
    if (TIKV_BUMPVERION_HASH.length() > 1 && repo == "tikv") {
        sha1 = TIKV_BUMPVERION_HASH
    }
    if (repo == "monitoring") {
        sha1 = get_sha(actualRepo, RELEASE_BRANCH)
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
        println "pramas: ${paramsBuild}"
        build job: "build-common",
                wait: true,
                parameters: paramsBuild
    }


    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${repo}"
    def imageName = repo
    if (repo == "monitoring") {
        imageName = "tidb-monitor-initializer"
    }
    // if current version not need multi-arch image, then use image image to distingush amd64 and arm64.
    // otherwise, use imageTag to distingush different version.
    // example1:
    // hub.pingcap.net/qa/tidb:v5.4.0-pre
    // hub.pingcap.net/qa/tidb-arm64:v5.4.0-pre
    // example2:
    // hub.pingcap.net/qa/tidb:v6.1.0-pre-amd64
    // hub.pingcap.net/qa/tidb:v6.1.0-pre-arm64
    if (arch == "arm64" && !NEED_MULTIARCH) {
        imageName = imageName + "-arm64"
    }
    def imageTag = IMAGE_TAG
    if (NEED_MULTIARCH) {
        imageTag = IMAGE_TAG + "-" + arch
    }


    def image = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${imageTag},pingcap/${imageName}:${imageTag}"
    if (params.DEBUG_MODE) { 
        image = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${imageTag}}"   
    }
    // version need multi-arch image, sync image from internal harbor to dockerhub
    if (NEED_MULTIARCH) {
        image = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${imageTag}"
    }
    if (failpoint) {
        image = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${imageTag}-failpoint"
    }

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
    println "pramas: ${paramsDocker}"
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
            println "pramas: ${paramsDockerForDebug}"
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
        if (params.DEBUG_MODE) {  
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
        if (NEED_MULTIARCH) {
            IMAGE_TAG = IMAGE_TAG + "-" + arch
        def imageLightling = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${IMAGE_TAG},pingcap/${imageName}:${IMAGE_TAG}"
        if (params.DEBUG_MODE) {  
            imageLightling = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${IMAGE_TAG}"
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
    if (RELEASE_TAG >= "v5.3.0") {
        builds["build ng-monitoring " + arch] = {
            release_one("ng-monitoring", arch, false)
        }
    } else {
        println("skip build ng-monitoring because only v5.3.0+ support")
    }

    failpointRepos = ["tidb", "pd", "tikv"]
    for (item in failpointRepos) {
        def product = "${item}"
        builds["build ${item} failpoint" + arch] = {
            release_one(product, arch, true)
        }
    }
}


def manifest_multiarch_image() {
    def imageNames = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "tidb-monitor-initializer", "dm", "tidb-lightning", "ng-monitoring"]
    def manifest_multiarch_builds = [:]
    for (imageName in imageNames) {
        def paramsManifest = [
            string(name: "AMD64_IMAGE", value: "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${IMAGE_TAG}-amd64"),
            string(name: "ARM64_IMAGE", value: "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${IMAGE_TAG}-arm64"),
            string(name: "MULTI_ARCH_IMAGE", value: "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${IMAGE_TAG}"),
        ]
        build job: "manifest-multiarch-common",
            wait: true,
            parameters: paramsManifest
        if (params.DEBUG_MODE) {
            println "run pipeline in debug mode, not push image to dockerhub"
        } else {
            def paramsSyncImage = [
                    string(name: 'triggered_by_upstream_ci', value: "pre-release-docker"),
                    string(name: 'SOURCE_IMAGE', value: "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${IMAGE_TAG}"),
                    string(name: 'TARGET_IMAGE', value: "pingcap/${imageName}:${IMAGE_TAG}"),
            ]
            println "paramsSyncImage: ${paramsSyncImage}"
            build(job: "jenkins-image-syncer", parameters: paramsSyncImage, wait: true, propagate: true)
        }
    }

    parallel manifest_multiarch_builds
}


stage("release") {
    node("${GO_BUILD_SLAVE}") {
        container("golang") {
            releaseRepos = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "monitoring", "dm"]
            builds = [:]
            release_docker(releaseRepos, builds, "amd64")

            if (RELEASE_BRANCH == "release-5.1" || RELEASE_BRANCH >= "release-5.4") {
                release_docker(releaseRepos, builds, "arm64")
            }

            parallel builds

            if (NEED_MULTIARCH) {
                manifest_multiarch_image()
            }
        }
    }
}


