HARBOR_REGISTRY_PROJECT_PREFIX = "hub.pingcap.net/${params.HUB_PROJECT}"
if (params.DEBUG_MODE) {
    HARBOR_REGISTRY_PROJECT_PREFIX = 'hub.pingcap.net/ee-debug'
    println('DEBUG_MODE is true, use hub.pingcap.net/ee-debug')
}

def get_dockerfile_url(product, is_enterprise, is_debug){
    def fileName = product
    if (product == "tidb" && is_enterprise){
        fileName = fileName + '-enterprise'
    }
    if (is_debug) {
        fileName = fileName + "-debug"
    }
    return "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/${fileName}.Dockerfile"
}

def get_image_str_for_community(product, arch, is_failpoint, is_debug) {
    def imageTag = params.IMAGE_TAG
    if (! imageTag){
        imageTag = params.RELEASE_TAG + "-rocky"+ "-pre"
    }
    def imageName = product
    if (product == "monitoring") {
        imageName = "tidb-monitor-initializer"
    }
    if (is_failpoint) {
        imageTag = imageTag + "-" + "failpoint"
    }
    if (arch){
        imageTag = imageTag + "-" + arch
    }
    return "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${imageTag}"
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

def release_one(repo, arch, failpoint) {
    def actualRepo = repo
    def hash = ""
    if (repo == "br") {
        actualRepo = "tidb"
        hash = "${TIDB_HASH}"
    }
    if (repo == "dumpling") {
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


    def dockerfile = get_dockerfile_url(repo, false, false)
    def image = get_image_str_for_community(repo, arch, failpoint, false)

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


    if (NEED_DEBUG_IMAGE.toBoolean() && arch == "amd64") {
        def dockerfileForDebug = get_dockerfile_url(repo, false, true)
        def imageForDebug = get_image_str_for_community(repo, "", failpoint, true)
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
            println "build debug image ${repo} ${arch}, params: ${paramsDockerForDebug}"
            build job: "docker-common",
                    wait: true,
                    parameters: paramsDockerForDebug
        } else {
            println "only amd64 of following repos can build debug image: [dumpling,ticdc,tidb-binlog,tidb,tikv,pd], but got: $repo"
        }
    }

    if (repo == "br") {
        println("start build tidb-lightning")
        def dockerfileLightning = get_dockerfile_url("tidb-lightning", false, false)
        def imageLightling = get_image_str_for_community("tidb-lightning", arch, false,false)
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

        if (NEED_DEBUG_IMAGE.toBoolean() && arch == "amd64") {
            def dockerfileLightningForDebug = get_dockerfile_url("tidb-lightning", false, true)
            def imageLightlingForDebug = get_image_str_for_community("tidb-lightning", "",failpoint,true)
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
        def product = item
        def product_show = product
        if (product_show == "br") {
            product_show = "br && tidb-lightning"
        }
        builds["build  " + product_show + " " + arch] = {
            release_one(product, arch, false)
        }
    }

    if (arch == "amd64" && params.NEED_FAILPOINT.toBoolean()) {
        failpointRepos = ["tidb", "pd", "tikv"]
        for (item in failpointRepos) {
            def product = "${item}"
            builds["build ${item} failpoint" + arch] = {
                release_one(product, arch, true)
            }
        }
    }

}

stage("docker images") {
    node("${GO_BUILD_SLAVE}") {
        container("golang") {
            releaseRepos = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "monitoring", "dm", "ng-monitoring"]
            builds = [:]
            release_docker(releaseRepos, builds, "amd64")
            release_docker(releaseRepos, builds, "arm64")
            parallel builds
        }
    }
}

stage("create multi-arch image") {
    def imageNames = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "tidb-monitor-initializer", "dm", "tidb-lightning", "ng-monitoring"]
    def manifest_multiarch_builds = [:]
    for (item in imageNames) {
        def product = item // important: groovy closure may use the last status of loop var
        manifest_multiarch_builds[product] = {
            def paramsManifest = [
                    string(name: "AMD64_IMAGE", value: get_image_str_for_community(product, "amd64", false, false)),
                    string(name: "ARM64_IMAGE", value: get_image_str_for_community(product, "arm64", false, false)),
                    string(name: "MULTI_ARCH_IMAGE", value: get_image_str_for_community(product, "", false, false)),
            ]
            println "paramsManifest: ${paramsManifest}"
            build job: "manifest-multiarch-common",
                    wait: true,
                    parameters: paramsManifest
        }
    }
    parallel manifest_multiarch_builds
}
