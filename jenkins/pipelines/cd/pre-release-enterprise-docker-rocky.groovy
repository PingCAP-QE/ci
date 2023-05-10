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

def get_image_str_for_enterprise(product, arch, tag) {
    def imageTag = tag + "-rocky" + "-pre"
    def imageName = product
    if (product == "monitroing") {
        imageName = "tidb-monitor-initializer"
    }
    imageName = imageName + "-enterprise"
    if (arch != ""){
        imageTag = imageTag + "-" + arch
    }
    def imageStr = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${imageName}:${imageTag}"
    return imageStr
}

def build_tidb_enterprise_image(product, sha1, plugin_hash, arch) {
    def binary = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${sha1}/centos7/${product}-linux-${arch}-enterprise.tar.gz"
    def plugin_binary = "builds/pingcap/enterprise-plugin/optimization/${RELEASE_TAG}/${plugin_hash}/centos7/enterprise-plugin-linux-${arch}-enterprise.tar.gz"
    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/${product}.Dockerfile"
    def inputBin = binary
    if (RELEASE_TAG < "v7.1.0") {
        dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/${product}-enterprise-v4.Dockerfile"
        inputBin = "${binary},${plugin_binary}"
    }
    def image = get_image_str_for_enterprise("tidb", arch, RELEASE_TAG)
    def paramsDocker = [
            string(name: "ARCH", value: arch),
            string(name: "OS", value: "linux"),
            string(name: "INPUT_BINARYS", value: inputBin),
            string(name: "REPO", value: product),
            string(name: "PRODUCT", value: product),
            string(name: "RELEASE_TAG", value: RELEASE_TAG),
            string(name: "DOCKERFILE", value: dockerfile),
            string(name: "RELEASE_DOCKER_IMAGES", value: image),
            string(name: "GIT_BRANCH", value: RELEASE_BRANCH),
    ]
    println "build tidb enterprise image: ${paramsDocker}"
    build job: "docker-common",
            wait: true,
            parameters: paramsDocker
}

def build_enterprise_image(product, sha1, arch) {
    def binary = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${sha1}/centos7/${product}-linux-${arch}-enterprise.tar.gz"
    if (product == "tidb-lightning") {
        binary = "builds/pingcap/br/optimization/${RELEASE_TAG}/${sha1}/centos7/br-linux-${arch}-enterprise.tar.gz"
    }
    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/${product}.Dockerfile"
    def repo = product
    def image = get_image_str_for_enterprise(product, arch, RELEASE_TAG)

    def paramsDocker = [
            string(name: "ARCH", value: arch),
            string(name: "OS", value: "linux"),
            string(name: "INPUT_BINARYS", value: binary),
            string(name: "REPO", value: repo),
            string(name: "PRODUCT", value: repo),
            string(name: "RELEASE_TAG", value: RELEASE_TAG),
            string(name: "DOCKERFILE", value: dockerfile),
            string(name: "RELEASE_DOCKER_IMAGES", value: image),
            string(name: "GIT_BRANCH", value: RELEASE_BRANCH),
    ]
    println "paramsDocker: ${paramsDocker}"
    build job: "docker-common",
            wait: true,
            parameters: paramsDocker
}


stage("build enterprise image"){
    def builds = [:]
    builds["tidb-amd64"] = {
        println "tidb hash : ${TIDB_HASH}"
        println "tidb plugin hash : ${PLUGIN_HASH}"
        build_tidb_enterprise_image("tidb", TIDB_HASH, PLUGIN_HASH, "amd64")
    }

    builds["tikv-amd64"] = {
        build_enterprise_image("tikv", TIKV_HASH, "amd64")
    }

    builds["pd-amd64"] = {
        build_enterprise_image("pd", PD_HASH, "amd64")
    }

    builds["tiflash-amd64"] = {
        build_enterprise_image("tiflash", TIFLASH_HASH, "amd64")
    }

    builds["tidb-arm64"] = {
        println "tidb hash : ${TIDB_HASH}"
        println "tidb plugin hash : ${PLUGIN_HASH}"
        build_tidb_enterprise_image("tidb", TIDB_HASH, PLUGIN_HASH, "arm64")
    }

    builds["tikv-arm64"] = {
        build_enterprise_image("tikv", TIKV_HASH, "arm64")
    }

    builds["pd-arm64"] = {
        build_enterprise_image("pd", PD_HASH, "arm64")
    }

    builds["tiflash-arm64"] = {
        build_enterprise_image("tiflash", TIFLASH_HASH, "arm64")
    }
    parallel builds
}

stage("manifest multi-arch"){
    def manifest_multiarch_builds = [:]
    for (item in ["tikv", "tidb", "tiflash", "pd"]) {
        def product = item
        manifest_multiarch_builds[product] = {
            def paramsManifest = [
                    string(name: "AMD64_IMAGE", value: get_image_str_for_enterprise(product, "amd64", RELEASE_TAG)),
                    string(name: "ARM64_IMAGE", value: get_image_str_for_enterprise(product, "arm64", RELEASE_TAG)),
                    string(name: "MULTI_ARCH_IMAGE", value: get_image_str_for_enterprise(product, "", RELEASE_TAG)),
            ]
            println "paramsManifest: ${paramsManifest}"
            build job: "manifest-multiarch-common",
                    wait: true,
                    parameters: paramsManifest
        }
    }
    parallel manifest_multiarch_builds
}

stage("retag community images"){
    def builds = [:]
    retagProducts = ["tidb-lightning", "tidb-binlog", "ticdc", "br", "dumpling", "ng-monitoring", "dm", "tidb-monitor-initializer"]
    for (item in retagProducts) {
        def product = item
        builds["retag ${product}"] = {
            def  community_image = "${HARBOR_REGISTRY_PROJECT_PREFIX}/${product}:${RELEASE_TAG}-rocky-pre"
            def  enterprise_image = get_image_str_for_enterprise(product, "", RELEASE_TAG)
            def default_params = [
                    string(name: 'SOURCE_IMAGE', value: community_image),
                    string(name: 'TARGET_IMAGE', value: enterprise_image),
            ]
            println "retag enterprise image from community image: ${default_params}"
            build(job: "jenkins-image-syncer",
                    parameters: default_params,
                    wait: true)
        }
    }
    parallel builds
}
