def check_file_exists(build_para, product) {
    if (build_para["FORCE_REBUILD"]) {
        return true
    }
    def arch = build_para["ARCH"]
    def os = build_para["OS"]
    def release_tag = build_para["RELEASE_TAG"]
    def sha1 = build_para[product]
    def FILE_SERVER_URL = build_para["FILE_SERVER_URL"]

    def filepath = "builds/pingcap/${product}/optimization/${release_tag}/${sha1}/${platform}/${product}-${os}-${arch}.tar.gz"
    
    result = sh(script: "curl -I ${FILE_SERVER_URL}/download/${filepath} -X \"HEAD\"|grep \"200 OK\"", returnStatus: true)
    // result equal 0 mean cache file exists
    if (result == 0) {
        echo "file ${FILE_SERVER_URL}/download/${filepath} found in cache server,skip build again"
        return true
    }
    return false
}

def create_builds(build_para) {
    builds = [:]

    builds["Build tidb-ctl"] = {
        build_product(build_para, "tidb-ctl")
    }
    builds["Build tikv"] = {
        build_product(build_para, "tikv")
    }
    builds["Build tidb"] = {
        build_product(build_para, "tidb")
    }
    builds["Build tidb-binlog"] = {
        build_product(build_para, "tidb-binlog")
    }
    builds["Build tidb-tools"] = {
        build_product(build_para, "tidb-tools")
    }
    builds["Build pd"] = {
        build_product(build_para, "pd")
    }
    builds["Build ticdc"] = {
        build_product(build_para, "ticdc")
    }
    builds["Build br"] = {
        build_product(build_para, "br")
    }
    builds["Build dumpling"] = {
        build_product(build_para, "dumpling")
    }
    if (release_tag >= "v5.3.0") {
        builds["Build NGMonitoring"] = {
            build_product(build_para, "ng-monitoring")
        }
    }

    if (release_tag < "v5.2.0" && build_para["OS"] == "linux") {
        builds["Build Importer"] = {
            build_product(build_para, "importer")
        }
    }

    if (build_para["OS"] == "linux" && build_para["ARCH"] == "amd64") {
        builds["Build Plugin"] = {
            build_product(build_para, "enterprise-plugin")
        }
    }

    builds["Build Tiflash"] = {
        build_product(build_para, "tiflash")
    }

    return builds
}

def create_enterprise_builds(build_para) {
    builds = [:]
    build_para["ENTERPRISE"] = true

    builds["Build tidb"] = {
        build_product(build_para, "tidb")
    }
    builds["Build tikv"] = {
        build_product(build_para, "tikv")
    }
    builds["Build pd"] = {
        build_product(build_para, "pd")
    }
    builds["Build tiflash"] = {
        build_product(build_para, "tiflash")
    }
    return builds
}

def build_product(build_para, product) {
    def arch = build_para["ARCH"]
    def os = build_para["OS"]
    def release_tag = build_para["RELEASE_TAG"]
    def platform = build_para["PLATFORM"]
    def sha1 = build_para[product]
    def git_pr = build_para["GIT_PR"]
    def force_rebuild = build_para["FORCE_REBUILD"]
    def repo = product

    if (release_tag >= "v5.2.0" && product == "br") {
        repo = "tidb"
    }
    if (release_tag >= "v5.3.0" && product == "dumpling") {
        repo = "tidb"
    }
    if (product == "ticdc") {
        repo = "tiflow"
    }

    def filepath = "builds/pingcap/${product}/optimization/${release_tag}/${sha1}/${platform}/${product}-${os}-${arch}.tar.gz"
    if (build_para["ENTERPRISE"]) {
        filepath = "builds/pingcap/${product}/optimization/${release_tag}/${sha1}/${platform}/${product}-${os}-${arch}-enterprise.tar.gz"
    }
    if (product == "tiflash") {
        repo = "tics"
        product = "tics"
    }

    def paramsBuild = [
        string(name: "ARCH", value: arch),
        string(name: "OS", value: os),
        string(name: "OUTPUT_BINARY", value: filepath),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: product),
        string(name: "GIT_HASH", value: sha1),
        string(name: "RELEASE_TAG", value: release_tag),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: force_rebuild],
    ]
    if (product in ["tidb", "tikv", "pd"]) {
        paramsBuild.push(booleanParam(name: 'NEED_SOURCE_CODE', value: true))   
    }
    if (git_pr != "" && repo == "tikv") {
        paramsBuild.push([$class: 'StringParameterValue', name: 'GIT_PR', value: git_pr])
    }
    if (product in ["enterprise-plugin"]) {
        paramsBuild.push([$class: 'StringParameterValue', name: 'TIDB_HASH', value: build_para["tidb"]])
    }
    if (build_para["ENTERPRISE"]) {
        paramsBuild.push(string(name: "EDITION", value: "enterprise"))
    } else {
        paramsBuild.push(string(name: "EDITION", value: "community"))
    }


    build job: "build-common", 
        wait: true, 
        parameters: paramsBuild
}

def release_online_image(product, sha1, arch,  os , platform,tag) {
    def binary = "builds/pingcap/${product}/optimization/${tag}/${sha1}/${platform}/${product}-${os}-${arch}.tar.gz"
    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${product}"
    def imageName = product
    def repo = product

    if (repo == "monitoring") {
        imageName = "tidb-monitor-initializer"
    }
    if (arch == "arm64") {
        imageName = imageName + "-arm64"
    }

    def image = "uhub.service.ucloud.cn/pingcap/${imageName}:${tag},pingcap/${imageName}:${tag}"

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
    build job: "docker-common",
            wait: true,
            parameters: paramsDocker
}

def release_online_arm_image(product, sha1, arch,  os , platform,tag) {
    def binary = "builds/pingcap/${product}/optimization/${tag}/${sha1}/${platform}/${product}-${os}-${arch}.tar.gz"
    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${product}"
    def imageName = product
    def repo = product

    if (repo == "monitoring") {
        imageName = "tidb-monitor-initializer"
    }
    if (arch == "arm64") {
        imageName = imageName + "-arm64"
    }

    def image = "pingcap/${imageName}:${tag}"

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
    build job: "docker-common",
            wait: true,
            parameters: paramsDocker
}

return this