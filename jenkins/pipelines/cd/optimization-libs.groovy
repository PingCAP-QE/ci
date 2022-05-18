env.DOCKER_HOST = "tcp://localhost:2375"

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

def test_binary_already_build(binary_url) {
    def cacheExisted = sh(returnStatus: true, script: """
    if curl --output /dev/null --silent --head --fail ${binary_url}; then exit 0; else exit 1; fi
    """)
    if (cacheExisted == 0) {
        return true
    } else {
        return false
    }
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
        // dm merged into tiflow from 5.3.0, only support build dm from v5.3.0
        builds["Build dm"] = {
            build_product(build_para, "dm")
        }
    }

    if (build_para["OS"] == "linux") {
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
    arch = build_para["ARCH"]

    builds["Build tidb ${arch}"] = {
        build_product(build_para, "tidb")
    }
    builds["Build tikv ${arch}"] = {
        build_product(build_para, "tikv")
    }
    builds["Build pd ${arch}"] = {
        build_product(build_para, "pd")
    }
    builds["Build tiflash ${arch}"] = {
        build_product(build_para, "tiflash")
    }
    builds["Build Plugin ${arch}"] = {
        build_product(build_para, "enterprise-plugin")
    }

    return builds
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
def get_image_str(product, arch, tag, if_release, if_multi_arch) {
    def imageStr = ""
    def imageTag = tag
    def imageName = product
    if (product == "monitroing") {
        imageName = "tidb-monitor-initializer"
    }
    if (!if_multi_arch && arch == "arm64") {
        imageName = imageName + "-arm64"
    }
    if (!if_release) {
        imageTag = imageTag + "-pre"
        if (if_multi_arch) {
            imageTag = imageTag + "-" + arch
        }
    }

    imageStr = "uhub.service.ucloud.cn/pingcap/${imageName}:${imageTag},pingcap/${imageName}:${imageTag}"
    if (!if_release) {
        imageStr = "hub.pingcap.net/qa/${imageName}:${imageTag}"
    }

    return imageStr
}

//new
def build_tidb_enterprise_image(product, sha1, plugin_hash, arch, if_release, if_multi_arch) {
    // build tidb enterprise image with plugin
    binary = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${sha1}/centos7/${product}-linux-${arch}-enterprise.tar.gz"
    plugin_binary = "builds/pingcap/enterprise-plugin/optimization/${RELEASE_TAG}/${plugin_hash}/centos7/enterprise-plugin-linux-${arch}-enterprise.tar.gz"

    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${product}"
    if (product == "tidb"  && arch == "amd64") {
        dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/enterprise/tidb"
    }
    if (product == "tidb"  && arch == "arm64") {
        dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-arm64/enterprise/tidb"
    }
    def image = get_image_str("tidb", arch, RELEASE_TAG, if_release, if_multi_arch)

    def paramsDocker = [
            string(name: "ARCH", value: arch),
            string(name: "OS", value: "linux"),
            string(name: "INPUT_BINARYS", value: "${binary},${plugin_binary}"),
            string(name: "REPO", value: repo),
            string(name: "PRODUCT", value: repo),
            string(name: "RELEASE_TAG", value: RELEASE_TAG),
            string(name: "DOCKERFILE", value: dockerfile),
            string(name: "RELEASE_DOCKER_IMAGES", value: image),
            string(name: "GIT_BRANCH", value: RELEASE_BRANCH),
    ]
    println "build tidb enterprise image: ${paramsDocker}.if_release:${if_release}"
    build job: "docker-common-check",
            wait: true,
            parameters: paramsDocker
}

//new
def parallel_enterprise_docker(arch, if_release, if_multi_arch) {
    def builds = [:]

    builds["Push tidb Docker"] = {
        build_tidb_enterprise_image("tidb", TIDB_HASH, PLUGIN_HASH, arch, if_release, if_multi_arch)
    }

    builds["Push tikv Docker"] = {
        build_enterprise_image("tikv", TIKV_HASH, arch, if_release, if_multi_arch)
    }

    builds["Push pd Docker"] = {
        build_enterprise_image("pd", PD_HASH, arch, if_release, if_multi_arch)
    }

    builds["Push tiflash Docker"] = {
        build_enterprise_image("tiflash", TIFLASH_HASH, arch, if_release, if_multi_arch)
    }

    retagProducts = ["tidb-lightning", "tidb-binlog", "ticdc", "br", "dumpling", "ng-monitoring", "dm"]
    for (item in retagProducts) {
        def product = item
        builds["Push ${product} Docker"] = {
            retag_enterprise_image(product, arch, if_release, if_multi_arch)
        }
    }
    
    stage("Push ${arch} enterprise image") {
        parallel builds
    }
}

//new
// version need multi-arch image, we don't build arm64 image like this
// pingcap/${product}-arm64:${RELEASE_TAG}, becase pingcap/${product}:${RELEASE_TAG} contains arm64 and amd64 image
def retag_enterprise_image(product, arch, if_release, if_multi_arch) {
    def community_image_for_pre_replease
    def enterprise_image_for_pre_replease
    if (arch == 'amd64' && if_release) {
        community_image_for_pre_replease = "pingcap/${product}:${RELEASE_TAG}"
        enterprise_image_for_pre_replease = "pingcap/${product}-enterprise:${RELEASE_TAG}"
    } else if (arch == 'arm64' && if_release) {
        community_image_for_pre_replease = "pingcap/${product}-arm64:${RELEASE_TAG}"
        enterprise_image_for_pre_replease = "pingcap/${product}-enterprise-arm64:${RELEASE_TAG}"
    } else if (arch == 'amd64' && (!if_release)) {
        community_image_for_pre_replease = "hub.pingcap.net/qa/${product}:${RELEASE_TAG}-pre"
        enterprise_image_for_pre_replease = "hub.pingcap.net/qa/${product}-enterprise:${RELEASE_TAG}-pre"
    } else {
        community_image_for_pre_replease = "hub.pingcap.net/qa/${product}-arm64:${RELEASE_TAG}-pre"
        enterprise_image_for_pre_replease = "hub.pingcap.net/qa/${product}-enterprise-arm64:${RELEASE_TAG}-pre"
    }

    if (if_multi_arch && arch == "arm64") {
        println "current version ${RELEASE_TAG} support multi-arch image, we don't retag arm64 image separately"
        return
    }


    def default_params = [
            string(name: 'SOURCE_IMAGE', value: community_image_for_pre_replease),
            string(name: 'TARGET_IMAGE', value: enterprise_image_for_pre_replease),
    ]
    println "retag enterprise image from community image: ${default_params}"
    build(job: "jenkins-image-syncer",
            parameters: default_params,
            wait: true)

}


//new
def build_enterprise_image(product, sha1, arch, if_release, if_multi_arch) {
    def binary = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${sha1}/centos7/${product}-linux-${arch}-enterprise.tar.gz"
    if (product == "tidb-lightning") {
        binary = "builds/pingcap/br/optimization/${RELEASE_TAG}/${sha1}/centos7/br-linux-${arch}-enterprise.tar.gz"
    }
    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${product}"
    def repo = product
    def image = get_image_str(product, arch, RELEASE_TAG, if_release, if_multi_arch)

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
    println "build enterprise image: ${paramsDocker}.if_release:${if_release}"
    println "paramsDocker: ${paramsDocker}"
    build job: "docker-common-check",
            wait: true,
            parameters: paramsDocker
}

def parallel_enterprise_docker_multiarch(if_release) {
    def manifest_multiarch_builds = [:]
    def imageNames = ["tikv", "tidb", "tiflash", "pd"]
    def imageTag = RELEASE_TAG
    if (!if_release) {
        imageTag = imageTag + "-pre"
    }
    for (item in imageNames) {
        def imageName = item
        def paramsManifest = [
            string(name: "AMD64_IMAGE", value: "hub.pingcap.net/qa/${imageName}:${imageTag}-amd64"),
            string(name: "ARM64_IMAGE", value: "hub.pingcap.net/qa/${imageName}:${imageTag}-arm64"),
            string(name: "MULTI_ARCH_IMAGE", value: "hub.pingcap.net/qa/${imageName}:${imageTag}"),
        ]
        println "paramsManifest: ${paramsManifest}"
        build job: "manifest-multiarch-common",
            wait: true,
            parameters: paramsManifest
        def paramsSyncImage = [
                string(name: 'triggered_by_upstream_ci', value: "release-enterprise-docker"),
                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/qa/${imageName}:${imageTag}"),
                string(name: 'TARGET_IMAGE', value: "pingcap/${imageName}:${imageTag}"),
        ]
        println "paramsSyncImage: ${paramsSyncImage}"
        build(job: "jenkins-image-syncer", parameters: paramsSyncImage, wait: true, propagate: true)
    }

    parallel manifest_multiarch_builds
    
}

def retag_enterprise_docker(product, release_tag, pre_release) {

    if (pre_release) {
        def community_image = "pingcap/${product}:${release_tag}"
        def enterprise_image = "pingcap/${product}-enterprise:${release_tag}"

        withDockerServer([uri: "${env.DOCKER_HOST}"]) {
            sh """
            docker pull ${community_image}
            docker tag ${community_image} ${enterprise_image}
            docker push ${enterprise_image}
            """
        }
    } else {
        def community_image_for_pre_replease = "hub.pingcap.net/qa/${product}:${release_tag}"
        def enterprise_image_for_pre_replease = "hub.pingcap.net/qa/${product}-enterprise:${release_tag}"

        def default_params = [
                string(name: 'SOURCE_IMAGE', value: community_image_for_pre_replease),
                string(name: 'TARGET_IMAGE', value: enterprise_image_for_pre_replease),
        ]
        build(job: "jenkins-image-syncer",
                parameters: default_params,
                wait: true, propagate: true)
    }

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
    if (product == "dm") {
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
    if (product == "enterprise-plugin") {
        force_rebuild = true
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

    println "paramsBuild: ${paramsBuild}"
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}

def release_online_image(product, sha1, arch, os, platform, tag, enterprise, preRelease) {
    def binary = "builds/pingcap/${product}/optimization/${tag}/${sha1}/${platform}/${product}-${os}-${arch}.tar.gz"
    if (product == "tidb-lightning") {
        binary = "builds/pingcap/br/optimization/${tag}/${sha1}/${platform}/br-${os}-${arch}.tar.gz"
    }
    if (enterprise) {
        binary = "builds/pingcap/${product}/optimization/${tag}/${sha1}/${platform}/${product}-${os}-${arch}-enterprise.tar.gz"
    }

    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${product}"
    if (enterprise && product == "tidb" && os == "linux" && arch == "amd64") {
        dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/enterprise/tidb"
    }
    def imageName = product
    def repo = product

    if (repo == "monitoring") {
        imageName = "tidb-monitor-initializer"
    }
    if (enterprise) {
        imageName = imageName + "-enterprise"
    }
    if (arch == "arm64") {
        imageName = imageName + "-arm64"
    }

    def image = "uhub.service.ucloud.cn/pingcap/${imageName}:${tag},pingcap/${imageName}:${tag}"
    // pre release stage, only push to harbor registry
    if (preRelease) {
        image = "hub.pingcap.net/qa/${imageName}:${tag}"
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
            string(name: "GIT_BRANCH", value: RELEASE_BRANCH),
    ]
    println "release_online_image: ${paramsDocker}"
    build job: "docker-common-check",
            wait: true,
            parameters: paramsDocker
}

def release_tidb_online_image(product, sha1, plugin_hash, arch, os, platform, tag, enterprise, preRelease) {
    // build tidb enterprise image with plugin
    def binary = "builds/pingcap/${product}/optimization/${tag}/${sha1}/${platform}/${product}-${os}-${arch}.tar.gz"
    def plugin_binary = "builds/pingcap/enterprise-plugin/optimization/${tag}/${plugin_hash}/${platform}/enterprise-plugin-${os}-${arch}.tar.gz"
    if (enterprise) {
        binary = "builds/pingcap/${product}/optimization/${tag}/${sha1}/${platform}/${product}-${os}-${arch}-enterprise.tar.gz"
        plugin_binary = "builds/pingcap/enterprise-plugin/optimization/${tag}/${plugin_hash}/${platform}/enterprise-plugin-${os}-${arch}-enterprise.tar.gz"
    }

    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${product}"
    if (enterprise && product == "tidb" && os == "linux" && arch == "amd64") {
        dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/enterprise/tidb"
    }
    def imageName = product
    def repo = product

    if (repo == "monitoring") {
        imageName = "tidb-monitor-initializer"
    }
    if (enterprise) {
        imageName = imageName + "-enterprise"
    }
    if (arch == "arm64") {
        imageName = imageName + "-arm64"
    }

    def image = "uhub.service.ucloud.cn/pingcap/${imageName}:${tag},pingcap/${imageName}:${tag}"
    // pre release stage, only push to harbor registry
    if (preRelease) {
        image = "hub.pingcap.net/qa/${imageName}:${tag}"
    }

    def paramsDocker = [
            string(name: "ARCH", value: arch),
            string(name: "OS", value: "linux"),
            string(name: "INPUT_BINARYS", value: "${binary},${plugin_binary}"),
            string(name: "REPO", value: repo),
            string(name: "PRODUCT", value: repo),
            string(name: "RELEASE_TAG", value: RELEASE_TAG),
            string(name: "DOCKERFILE", value: dockerfile),
            string(name: "RELEASE_DOCKER_IMAGES", value: image),
            string(name: "GIT_BRANCH", value: RELEASE_BRANCH),
    ]
    println "release_online_image: ${paramsDocker}"
    build job: "docker-common-check",
            wait: true,
            parameters: paramsDocker
}


def release_dm_ansible_amd64(sha1, release_tag) {
    node('delivery') {
        container("delivery") {
            stage('Prepare') {
                def wss = pwd()
                def dm_file = "${FILE_SERVER_URL}/builds/pingcap/dm/optimization/${release_tag}/${sha1}/centos7/dm-linux-amd64.tar.gz"
                sh """
                    rm -rf *
                    cd /home/jenkins
                    mkdir -p .docker
                    cp /etc/dockerconfig.json .docker/config.json
                    cp -R /etc/.aws ./
                    cd $wss
                """
                dir('centos7') {
                    sh "curl ${dm_file} | tar xz"
                    // do not release dm-ansible after v6.0.0
                    // if (release_tag.startsWith("v") && release_tag <"v6.0.0") {
                    //   sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dm/${dm_sha1}/centos7/dm-ansible.tar.gz | tar xz"
                    // }
                }
            }

            stage('Push dm binary') {
                def target = "dm-${release_tag}-linux-amd64"
                dir("${target}") {
                    sh "cp -R ../centos7/* ./"
                }
                sh """
                    tar czvf ${target}.tar.gz ${target}
                    sha256sum ${target}.tar.gz > ${target}.sha256
                    md5sum ${target}.tar.gz > ${target}.md5
                """
                sh """
                    export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
                    upload.py ${target}.tar.gz ${target}.tar.gz
                    upload.py ${target}.sha256 ${target}.sha256
                    upload.py ${target}.md5 ${target}.md5
                """
                sh """
                    aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
                    aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
                    aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
                """
            }

            // do not release dm-ansible after v6.0.0
            if (release_tag.startsWith("v") && release_tag < "v6.0.0") {
                stage('Push dm-ansible package') {
                    def target = "dm-ansible-${release_tag}"
                    sh """
                    if [ ! -d "centos7/dm-ansible" ]; then
                        echo "not found dm-ansible, is something wrong?"
                        exit 1
                    fi
                    """
                    dir("${target}") {
                        sh "cp -R ../centos7/dm-ansible/* ./"
                    }
                    sh """
                        tar czvf ${target}.tar.gz ${target}
                        sha256sum ${target}.tar.gz > ${target}.sha256
                        md5sum ${target}.tar.gz > ${target}.md5
                    """
                    sh """
                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
                        upload.py ${target}.tar.gz ${target}.tar.gz
                        upload.py ${target}.sha256 ${target}.sha256
                        upload.py ${target}.md5 ${target}.md5
                    """
                    sh """
                        aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
                        aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
                        aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
                    """
                }
            }
        }
    }
}

return this
