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

    return builds
}

def build_product(build_para, product) {
    def arch = build_para["ARCH"]
    def os = build_para["OS"]
    def release_tag = build_para["RELEASE_TAG"]
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
    def paramsBuild = [
        string(name: "ARCH", value: arch),
        string(name: "OS", value: os),
        string(name: "EDITION", value: "community"),
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

    build job: "build-common", 
        wait: true, 
        parameters: paramsBuild
}

return this