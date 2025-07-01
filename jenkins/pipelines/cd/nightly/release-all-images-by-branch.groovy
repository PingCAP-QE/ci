/*
* @GIT_BRANCH(string:repo branch, Required)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
*/


taskStartTimeInMillis = System.currentTimeMillis()
taskFinishTimeInMillis = System.currentTimeMillis()

begin_time = new Date().format('yyyy-MM-dd HH:mm:ss')
ng_monitoring_sha1 = ""
dm_sha1 = ""
tidb_sha1 = ""
tikv_sha1 = ""
pd_sha1 = ""

tidb_lightning_sha1 = ""
tidb_br_sha1 = ""
tidb_binlog_sha1 = ""

tiflash_sha1 = ""
cdc_sha1 = ""
dm_sha1 = ""
dumpling_sha1 = ""
tidb_monitor_initializer_sha1 = ""
monitoring_sha1 = ""
tiflow_sha1 = ""

// ***
// thress type image: noremal, failpoint, debug, multiArch
//   type normal: use binary from build-common: like tidb-server or tikv-server
//   type failpoint: use binary from build-common but enable failpoint: like tidb-server-failpoint or tikv-server-failpoint
//   type debug: use binary from build-common and use diffrent image Dockerfile (use ceontos7 as base image other than alpine)

// type normal & failpoint & debug commonly build a single arch image: just linux-amd64 or just linux-arm64
// type multiArch build a multi arch image: build two arch image: linux-amd64 and linux-arm64 then merge them by manifest-tool
//   example: ${HARBOR_PROJECT_PREFIX}/tidb:master is valid for both linux-amd64 and linux-arm64
//            it contains two images: ${HARBOR_PROJECT_PREFIX}/tidb:master-linux-amd64 and ${HARBOR_PROJECT_PREFIX}/tidb:master-linux-arm64
// ***

string trimPrefix = {
    it.startsWith('release-') ? it.minus('release-').split("-")[0] : it
}

HARBOR_PROJECT_PREFIX = "hub.pingcap.net/qa"


// for master branch: use default local tag: v6.1.0-nightly
RELEASE_TAG = "v8.5.0-alpha"
if (GIT_BRANCH.startsWith("release-")) {
    RELEASE_TAG = "v" + trimPrefix(GIT_BRANCH) + ".0-nightly"
}


def get_sha(repo) {
    return ["monitoring":monitoring_sha1,"tics":tiflash_sha1, "tiflash":tiflash_sha1,"tidb":tidb_sha1, "tikv":tikv_sha1,
        "pd":pd_sha1, "tiflow":tiflow_sha1, "tidb-binlog":tidb_binlog_sha1, "ng-monitoring":ng_monitoring_sha1,
        "br": tidb_br_sha1].getOrDefault(repo.toString(), "")
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

def startBuildBinary(arch, binary, actualRepo, repo, sha1, failpoint) {

    def paramsBuild = [
            string(name: "ARCH", value: arch),
            string(name: "OS", value: "linux"),
            string(name: "EDITION", value: "community"),
            string(name: "OUTPUT_BINARY", value: binary),
            string(name: "REPO", value: actualRepo),
            string(name: "PRODUCT", value: repo),
            string(name: "GIT_HASH", value: sha1),
            string(name: "RELEASE_TAG", value: RELEASE_TAG),
            string(name: "TARGET_BRANCH", value: GIT_BRANCH),
            string(name: "USE_TIFLASH_RUST_CACHE", value: 'true'),
            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
            [$class: 'BooleanParameterValue', name: 'FAILPOINT', value: failpoint],
    ]

    if (repo == "tics-debug" && GIT_BRANCH == "master") {
        paramsBuild = [
                string(name: "ARCH", value: arch),
                string(name: "OS", value: "linux"),
                string(name: "EDITION", value: "community"),
                string(name: "OUTPUT_BINARY", value: binary),
                string(name: "REPO", value: actualRepo),
                string(name: "PRODUCT", value: "tics"),
                string(name: "GIT_HASH", value: sha1),
                string(name: "RELEASE_TAG", value: RELEASE_TAG),
                string(name: "TARGET_BRANCH", value: GIT_BRANCH),
                string(name: "USE_TIFLASH_RUST_CACHE", value: 'true'),
                [$class: 'BooleanParameterValue', name: 'TIFLASH_DEBUG', value: true],
                [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                [$class: 'BooleanParameterValue', name: 'FAILPOINT', value: failpoint],
        ]
    }

    println "paramsBuild: ${paramsBuild}"

    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}


def parseBuildInfo(repo) {
    def actualRepo = repo
    if (repo == "br" && GIT_BRANCH == "master") {
        actualRepo = "tidb"
    }
    // Notice: the code of br has been merged to tidb from release-5.2, so we need to use tidb as actual repo
    if (repo == "br" && GIT_BRANCH.startsWith("release-") && GIT_BRANCH >= "release-5.2") {
        actualRepo = "tidb"
    }
    if (repo == "dumpling" && GIT_BRANCH == "master") {
        actualRepo = "tidb"
    }
    // Notice: the code of dumpling has been merged to tidb from release-5.3, so we need to use tidb as actual repo
    if (repo == "dumpling" && GIT_BRANCH.startsWith("release-") && GIT_BRANCH >= "release-5.3") {
        actualRepo = "tidb"
    }
    // Notice: repo ticdc has been renamed to tiflow from 2022/0/01, so we need to use tiflow as actual repo
    if (repo == "ticdc") {
        actualRepo = "tiflow"
    }
    // Notice: dm has been merged to tiflow from release-5.3.0, so we need to use tiflow as actual repo
    // only support dm build from v5.3.0
    if (repo == "dm") {
        actualRepo = "tiflow"
    }
    // if (repo == "tiflash") {
    //     actualRepo = "tics"
    // }
    if (repo == "tics-debug") {
        actualRepo = "tiflash"
    }
    // TODO: tidb-lightning is so complex !!!
    // tidb-lightning is a part of br, and br is merged to tidb from release-5.2, so we need to use tidb as actual repo
    if (repo == "tidb-lightning") {
        // Notice: the code of br has been merged to tidb from release-5.2, so we need to use tidb as actual repo
        if (GIT_BRANCH.startsWith("release-") && GIT_BRANCH >= "release-5.2") {
            actualRepo = "tidb"
        } else if (GIT_BRANCH == "master") {
            actualRepo = "tidb"
        } else {
            actualRepo = "br"
        }
    }
    def sha1 = get_sha(actualRepo)
    if (sha1.length() == 40) {
        println "valid sha1: ${sha1}"
    } else {
        println "invalid $actualRepo sha1: ${sha1}"
        currentBuild.result = "FAILURE"
        println "ERROR: can not get sha1 for ${repo} ${GIT_BRANCH}"
        throw new Exception("can not get sha1 for ${repo} ${GIT_BRANCH}")
    }
    println "repo: ${repo}, actualRepo: ${actualRepo}, sha1: ${sha1}"

    def binaryAmd64 = "builds/pingcap/qa-daily-image-build/${repo}/${GIT_BRANCH}/${sha1}/centos7/${repo}-linux-amd64.tar.gz"
    def binaryArm64 = "builds/pingcap/qa-daily-image-build/${repo}/${GIT_BRANCH}/${sha1}/centos7/${repo}-linux-arm64.tar.gz"
    def binaryAmd64Failpoint = "builds/pingcap/qa-daily-image-build/${repo}/${GIT_BRANCH}/${sha1}/centos7/${repo}-linux-amd64-failpoint.tar.gz"
    def binaryArm64Failpoint = "builds/pingcap/qa-daily-image-build/${repo}/${GIT_BRANCH}/${sha1}/centos7/${repo}-linux-arm64-failpoint.tar.gz"

    if (repo == "tics-debug") {
        repo = "tics"
    }
    def dockerfileAmd64 = get_dockerfile_url('amd64', repo, false)
    def dockerfileArm64 = get_dockerfile_url('arm64', repo, false)

    if (repo == "tidb-lightning") {
        // Notice: the code of br has been merged to tidb from release-5.2, so we need to use tidb binary
        // tar package of tidb build by atom-job include these binaries:
        //
        // example: download/builds/pingcap/br/master/3e1cd2733a8e43670b25e7b2e53001eccac78147/centos7/br.tar.gz
        binaryAmd64 = "builds/pingcap/qa-daily-image-build/br/${GIT_BRANCH}/${sha1}/centos7/br-linux-amd64.tar.gz"
        binaryArm64 = "builds/pingcap/qa-daily-image-build/br/${GIT_BRANCH}/${sha1}/centos7/br-linux-arm64.tar.gz"
        binaryAmd64Failpoint = "builds/pingcap/qa-daily-image-build/br/${GIT_BRANCH}/${sha1}/centos7/br-linux-amd64-failpoint.tar.gz"
        binaryArm64Failpoint = "builds/pingcap/qa-daily-image-build/br/${GIT_BRANCH}/${sha1}/centos7/br-linux-arm64-failpoint.tar.gz"
    }

    return [
            "repo"                            : repo,
            "sha1"                            : sha1,
            "actualRepo"                      : "${actualRepo}",
            "binaryAmd64"                     : binaryAmd64,
            "binaryArm64"                     : binaryArm64,
            "binaryAmd64Failpoint"            : binaryAmd64Failpoint,
            "binaryArm64Failpoint"            : binaryArm64Failpoint,
            "dockerfileAmd64"                 : dockerfileAmd64,
            "dockerfileArm64"                 : dockerfileArm64,
            "dockerfileForDebugAmd64"         : get_dockerfile_url('amd64',repo,true),
            // "dockerfileForDebugArm64": "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-arm64/${repo}",  // TODO: arm64 have not unique debug image Dockerfile
            "imageName"                       : "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}",
            "imageNameEnableFailpoint"        : "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}-failpoint",
            "imageNameForDebug"               : "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}-debug",
            "imageNameForDebugEnableFailpoint": "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}-debug-failpoint",
            "imageNameAmd64"                  : "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}-linux-amd64",
            "imageNameArm64"                  : "${HARBOR_PROJECT_PREFIX}/${repo}:${GIT_BRANCH}-linux-arm64",
    ]
}

def get_dockerfile_url(arch, repo, isDebug){
    def Product = repo
    if (repo == 'tics'){
        Product = 'tiflash'
    }
    def fileName = Product
    if (RELEASE_TAG >='v6.6.0'){
        if (Product == 'tiflash'){
            "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${fileName}_nightly"
        }
        return "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/${fileName}.Dockerfile"
    }else{
        if (isDebug){
            return "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/debug-image/${fileName}"
        }
        return "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${fileName}"
    }
}

def release_one_normal(repo) {
    def buildInfo = parseBuildInfo(repo)
    def buildRepo = buildInfo.actualRepo
    def buildProduct = repo
    if (repo == "tidb-lightning") {
        buildProduct = "br"
    }

    stage("build binary") {
        if (test_binary_already_build("${FILE_SERVER_URL}/download/${buildInfo.binaryAmd64}") && !params.FORCE_REBUILD) {
            echo "binary(amd64) already build: ${buildInfo.binaryAmd64}"
        } else {
            echo "build binary(amd64): ${buildInfo.binaryAmd64}"
            startBuildBinary("amd64", buildInfo.binaryAmd64, buildRepo, buildProduct, buildInfo.sha1, false)
        }
        if (params.NEED_MULTIARCH) {
            if (test_binary_already_build("${FILE_SERVER_URL}/download/${buildInfo.binaryArm64}") && !params.FORCE_REBUILD) {
                echo "binary already build(arm64): ${buildInfo.binaryArm64}"
            } else {
                echo "build binary(arm64): ${buildInfo.binaryArm64}"
                startBuildBinary("arm64", buildInfo.binaryArm64, buildRepo, buildProduct, buildInfo.sha1, false)
            }
        }
    }

    if (params.NEED_MULTIARCH) {
        println "build multi arch image"
        def multiArchImage = buildInfo.imageName

        def dockerRepo = buildInfo.actualRepo
        def dockerProduct = repo
        def amd64Images = buildInfo.imageNameAmd64
        def arm64Images = buildInfo.imageNameArm64
        if (repo == "tidb-lightning") {
            dockerProduct = "br"
        }
        if (repo == "tics") {
            amd64Images = "${buildInfo.imageNameAmd64},${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}-linux-amd64"
            arm64Images = "${buildInfo.imageNameArm64},${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}-linux-arm64"
        }

        if (repo == "tics-debug") {
            amd64Images = "${buildInfo.imageNameAmd64},${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}-debug-linux-amd64"
            arm64Images = "${buildInfo.imageNameArm64},${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}-debug-linux-arm64"
            dockerProduct = "tics"
        }

        stage("build amd64 image") {
            def paramsDockerAmd64 = [
                    string(name: "ARCH", value: "amd64"),
                    string(name: "OS", value: "linux"),
                    string(name: "INPUT_BINARYS", value: buildInfo.binaryAmd64),
                    string(name: "REPO", value: dockerRepo),
                    string(name: "PRODUCT", value: dockerProduct),
                    string(name: "RELEASE_TAG", value: RELEASE_TAG),
                    string(name: "DOCKERFILE", value: buildInfo.dockerfileAmd64),
                    string(name: "RELEASE_DOCKER_IMAGES", value: amd64Images),
                    string(name: "GIT_BRANCH", value: GIT_BRANCH),
            ]
            build job: "docker-common",
                    wait: true,
                    parameters: paramsDockerAmd64
        }

        stage("build arm64 image") {
            def paramsDockerArm64 = [
                    string(name: "ARCH", value: "arm64"),
                    string(name: "OS", value: "linux"),
                    string(name: "INPUT_BINARYS", value: buildInfo.binaryArm64),
                    string(name: "REPO", value: dockerRepo),
                    string(name: "PRODUCT", value: dockerProduct),
                    string(name: "RELEASE_TAG", value: RELEASE_TAG),
                    string(name: "DOCKERFILE", value: buildInfo.dockerfileArm64),
                    string(name: "RELEASE_DOCKER_IMAGES", value: arm64Images),
                    string(name: "GIT_BRANCH", value: GIT_BRANCH),
            ]
            build job: "docker-common",
                    wait: true,
                    parameters: paramsDockerArm64
        }

        stage("manifest multiarch image") {
            // start manifest-tool to make multi arch image
            node("delivery") {
                container("delivery") {
                    withCredentials([usernamePassword(credentialsId: 'harbor-pingcap', usernameVariable: 'harborUser', passwordVariable: 'harborPassword')]) {
                        sh """
                    docker login -u ${harborUser} -p ${harborPassword} hub.pingcap.net
                    cat <<EOF > manifest-${repo}-${GIT_BRANCH}.yaml
image: ${multiArchImage}
manifests:
-
    image: ${buildInfo.imageNameArm64}
    platform:
    architecture: arm64
    os: linux
-
    image: ${buildInfo.imageNameAmd64}
    platform:
    architecture: amd64
    os: linux
EOF
                    cat manifest-${repo}-${GIT_BRANCH}.yaml
                    curl -o manifest-tool ${FILE_SERVER_URL}/download/cicd/tools/manifest-tool-linux-amd64
                    chmod +x manifest-tool
                    ./manifest-tool push from-spec manifest-${repo}-${GIT_BRANCH}.yaml
                    """
                        if (repo == "tics") {
                            sh """
                        docker login -u ${harborUser} -p ${harborPassword} hub.pingcap.net
                        cat <<EOF > manifest-tiflash-${GIT_BRANCH}.yaml
image: ${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}
manifests:
-
    image: ${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}-linux-arm64
    platform:
    architecture: arm64
    os: linux
-
    image: ${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}-linux-amd64
    platform:
    architecture: amd64
    os: linux
EOF
                        cat manifest-${repo}-${GIT_BRANCH}.yaml
                        curl -o manifest-tool ${FILE_SERVER_URL}/download/cicd/tools/manifest-tool-linux-amd64
                        chmod +x manifest-tool
                        ./manifest-tool push from-spec manifest-tiflash-${GIT_BRANCH}.yaml
                        """
                        }
                    }
                    archiveArtifacts artifacts: "manifest-${repo}-${GIT_BRANCH}.yaml", fingerprint: true
                    if (repo == "tics") {
                        archiveArtifacts artifacts: "manifest-tiflash-${GIT_BRANCH}.yaml", fingerprint: true
                    }
                }
                println "multi arch image: ${multiArchImage}"
            }
        }
    } else {
        println "build single arch image ${repo} (linux amd64)"
        def dockerProduct = repo
        def amd64Images = buildInfo.imageNameAmd64

        if (repo == "tidb-lightning") {
            dockerProduct = "br"
        }
        if (repo == "tics") {
            amd64Images = "${buildInfo.imageName},${HARBOR_PROJECT_PREFIX}/tiflash:${GIT_BRANCH}"
        }

        if (repo == "tics-debug" && GIT_BRANCH == "master") {
            amd64Images = "${buildInfo.imageNameForDebug},${HARBOR_PROJECT_PREFIX}/tiflash:master-debug"
            dockerProduct = "tics"
        }

        def paramsDockerAmd64 = [
                string(name: "ARCH", value: "amd64"),
                string(name: "OS", value: "linux"),
                string(name: "INPUT_BINARYS", value: buildInfo.binaryAmd64),
                string(name: "REPO", value: buildInfo.actualRepo),
                string(name: "PRODUCT", value: dockerProduct),
                string(name: "RELEASE_TAG", value: RELEASE_TAG),
                string(name: "DOCKERFILE", value: buildInfo.dockerfileAmd64),
                string(name: "RELEASE_DOCKER_IMAGES", value: amd64Images),
                string(name: "GIT_BRANCH", value: GIT_BRANCH),
        ]

        build job: "docker-common",
                wait: true,
                parameters: paramsDockerAmd64

        if (repo != "tics") {
            def sourceImage=buildInfo.imageNameAmd64
            sync_dest_image_name = sourceImage.replace("-linux-amd64", "")
            sync_image_params = [
                    string(name: 'triggered_by_upstream_ci', value: "docker-common-nova"),
                    string(name: 'SOURCE_IMAGE', value: sourceImage),
                    string(name: 'TARGET_IMAGE', value: sync_dest_image_name),
            ]
            build(job: "jenkins-image-syncer", parameters: sync_image_params, wait: true, propagate: true)
        }
    }
}


// only tikv / pd / br / tidb support enable failpoint
def release_one_enable_failpoint(repo) {
    stage("build failpoint image") {
        def buildInfo = parseBuildInfo(repo)
        def buildRepo = buildInfo.actualRepo
        def buildProduct = repo
        if (repo == "tidb-lightning") {
            buildProduct = "br"
        }
        if (test_binary_already_build("${FILE_SERVER_URL}/download/${buildInfo.binaryAmd64Failpoint}") && !params.FORCE_REBUILD) {
            echo "binary(amd64) already build: ${buildInfo.binaryAmd64Failpoint}"
        } else {
            echo "build binary(amd64): ${buildInfo.binaryAmd64Failpoint}"
            startBuildBinary("amd64", buildInfo.binaryAmd64Failpoint, buildRepo, buildProduct, buildInfo.sha1, true)
        }

        def dockerRepo = buildInfo.actualRepo
        def dockerProduct = repo
        if (repo == "tidb-lightning") {
            dockerProduct = "br"
        }

        println "build single arch image (linux amd64) with failpoint"
        println "image with binary enable failpoint: ${buildInfo.imageNameAmd64Failpoint}"

        def paramsDockerFailpoint = [
                string(name: "ARCH", value: "amd64"),
                string(name: "OS", value: "linux"),
                string(name: "INPUT_BINARYS", value: buildInfo.binaryAmd64Failpoint),
                string(name: "REPO", value: dockerRepo),
                string(name: "PRODUCT", value: dockerProduct),
                string(name: "RELEASE_TAG", value: RELEASE_TAG),
                string(name: "DOCKERFILE", value: buildInfo.dockerfileAmd64),
                string(name: "RELEASE_DOCKER_IMAGES", value: buildInfo.imageNameEnableFailpoint),
                string(name: "GIT_BRANCH", value: GIT_BRANCH),
        ]
        println "paramsDockerFailpoint: ${paramsDockerFailpoint}"
        build job: "docker-common",
                wait: true,
                parameters: paramsDockerFailpoint
    }
}

def release_one_debug(repo) {
    stage("build amd64 debug image") {
        def buildInfo = parseBuildInfo(repo)
        def buildRepo = buildInfo.actualRepo
        def buildProduct = repo
        if (repo == "tidb-lightning") {
            buildProduct = "br"
        }
        if (test_binary_already_build("${FILE_SERVER_URL}/download/${buildInfo.binaryAmd64}") && !FORCE_REBUILD) {
            echo "binary(amd64) already build: ${buildInfo.binaryAmd64}"
        } else {
            echo "build binary(amd64): ${buildInfo.binaryAmd64}"
            startBuildBinary("amd64", buildInfo.binaryAmd64, buildRepo, buildProduct, buildInfo.sha1, false)
        }
        def dockerRepo = buildInfo.actualRepo
        def dockerProduct = repo
        if (repo == "tidb-lightning") {
            dockerProduct = "br"
        }

        println "build single arch image (linux amd64) with debug dockerfile"
        println "debug image: ${buildInfo.imageNameForDebug}"
        def paramsDocker = [
                string(name: "ARCH", value: "amd64"),
                string(name: "OS", value: "linux"),
                string(name: "INPUT_BINARYS", value: buildInfo.binaryAmd64),
                string(name: "REPO", value: dockerRepo),
                string(name: "PRODUCT", value: dockerProduct),
                string(name: "RELEASE_TAG", value: RELEASE_TAG),
                string(name: "DOCKERFILE", value: buildInfo.dockerfileForDebugAmd64),
                string(name: "RELEASE_DOCKER_IMAGES", value: buildInfo.imageNameForDebug),
                string(name: "GIT_BRANCH", value: GIT_BRANCH),
        ]
        println "paramsDocker: ${paramsDocker}"
        build job: "docker-common",
                wait: true,
                parameters: paramsDocker
    }

}


def release_master_monitoring() {
    def sha1 = get_sha("monitoring")
    tidb_monitor_initializer_sha1 = sha1
    if (sha1.length() == 40) {
        println "valid sha1: ${sha1}"
    } else {
        println "invalid sha1: ${sha1}"
        currentBuild.result = "FAILURE"
        throw new Exception("Invalid sha1: ${sha1}, Throw to stop pipeline")
    }
    def binary = "builds/pingcap/monitoring/test/master/${sha1}/linux-amd64/monitoring.tar.gz"
    def arch = "amd64"
    // The monitoring cmd tool fetches all the monitoring json from each repo, so we don't use
    // cache and force a rebuild each time
    // releate to: https://github.com/pingcap/monitoring/tree/master/cmd
    def paramsBuild = [
            string(name: "ARCH", value: arch),
            string(name: "OS", value: "linux"),
            string(name: "EDITION", value: "community"),
            string(name: "OUTPUT_BINARY", value: binary),
            string(name: "REPO", value: "monitoring"),
            string(name: "PRODUCT", value: "monitoring"),
            string(name: "GIT_HASH", value: sha1),
            string(name: "RELEASE_TAG", value: "master"),
            string(name: "TARGET_BRANCH", value: "master"),
            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
    ]
    println "paramsBuild: ${paramsBuild}"

    build job: "build-common",
            wait: true,
            parameters: paramsBuild

    def binaryArm = "builds/pingcap/monitoring/test/master/${sha1}/linux-arm64/monitoring.tar.gz"
    def archArm = "arm64"
    def paramsBuildArm = [
            string(name: "ARCH", value: archArm),
            string(name: "OS", value: "linux"),
            string(name: "EDITION", value: "community"),
            string(name: "OUTPUT_BINARY", value: binaryArm),
            string(name: "REPO", value: "monitoring"),
            string(name: "PRODUCT", value: "monitoring"),
            string(name: "GIT_HASH", value: sha1),
            string(name: "RELEASE_TAG", value: "master"),
            string(name: "TARGET_BRANCH", value: "master"),
            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
    ]
    println "paramsBuild: ${paramsBuildArm}"

    build job: "build-common",
            wait: true,
            parameters: paramsBuildArm

    def imageNameAmd64 = "${HARBOR_PROJECT_PREFIX}/tidb-monitor-initializer:master-amd64"
    def paramsDockerAmd64 = [
            string(name: "ARCH", value: "amd64"),
            string(name: "OS", value: "linux"),
            string(name: "INPUT_BINARYS", value: binary),
            string(name: "REPO", value: "monitoring"),
            string(name: "PRODUCT", value: "monitoring"),
            string(name: "RELEASE_TAG", value: ""),
            string(name: "DOCKERFILE", value: ""),
            string(name: "RELEASE_DOCKER_IMAGES", value: imageNameAmd64),
            string(name: "GIT_BRANCH", value: GIT_BRANCH),
    ]
    build job: "docker-common",
            wait: true,
            parameters: paramsDockerAmd64
    def imageNameArm64 = "${HARBOR_PROJECT_PREFIX}/tidb-monitor-initializer:master-arm64"
    def paramsDockerArm64 = [
            string(name: "ARCH", value: "arm64"),
            string(name: "OS", value: "linux"),
            string(name: "INPUT_BINARYS", value: binaryArm),
            string(name: "REPO", value: "monitoring"),
            string(name: "PRODUCT", value: "monitoring"),
            string(name: "RELEASE_TAG", value: ""),
            string(name: "DOCKERFILE", value: ""),
            string(name: "RELEASE_DOCKER_IMAGES", value: imageNameArm64),
            string(name: "GIT_BRANCH", value: GIT_BRANCH),
    ]
    build job: "docker-common",
            wait: true,
            parameters: paramsDockerArm64
    def multiArchImage = "${HARBOR_PROJECT_PREFIX}/tidb-monitor-initializer:master"
    stage("manifest multiarch image") {
        // start manifest-tool to make multi arch image
        node("delivery") {
            container("delivery") {
                withCredentials([usernamePassword(credentialsId: 'harbor-pingcap', usernameVariable: 'harborUser', passwordVariable: 'harborPassword')]) {
                    sh """
                    docker login -u ${harborUser} -p ${harborPassword} hub.pingcap.net
                    cat <<EOF > manifest-monitoring-master.yaml
image: ${multiArchImage}
manifests:
-
    image: ${imageNameArm64}
    platform:
    architecture: arm64
    os: linux
-
    image: ${imageNameAmd64}
    platform:
    architecture: amd64
    os: linux
EOF
                    cat manifest-monitoring-master.yaml
                    curl -o manifest-tool ${FILE_SERVER_URL}/download/cicd/tools/manifest-tool-linux-amd64
                    chmod +x manifest-tool
                    ./manifest-tool push from-spec manifest-monitoring-master.yaml
                    """
                }
                archiveArtifacts artifacts: "manifest-monitoring-master.yaml", fingerprint: true
            }
        }
        println "multi arch image: ${multiArchImage}"
    }

}

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes"
    def namespace = "jenkins-cd"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: true,
                            image: "hub.pingcap.net/jenkins/centos7_golang-1.18:latest", ttyEnabled: true,
                            resourceRequestCpu: '200m', resourceRequestMemory: '1Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]
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

try {
    getHash()
    run_with_pod {
        container("golang") {
            builds = [:]
            if ("${GIT_BRANCH}" == "master") {
                builds["monitoring"] = {
                    retry(2) {
                        release_master_monitoring()
                    }
                }
            }
            releaseRepos = ["tics"]
            if ("${GIT_BRANCH}" == "master") {
                releaseRepos = ["tics", "tics-debug"]
            }
            for (item in releaseRepos) {
                def product = "${item}"
                builds["${item}-build"] = {
                    retry(2) {
                        release_one_normal(product)
                    }
                }
            }
            releaseReposMultiArch = ["tidb", "tikv", "pd", "br", "tidb-lightning", "ticdc", "dumpling", "tidb-binlog"]
            if ("${GIT_BRANCH}" >= "release-5.3" || "${GIT_BRANCH}" == "master") {
                releaseReposMultiArch = ["tidb", "tikv", "pd", "br", "tidb-lightning", "ticdc", "dumpling", "tidb-binlog", "dm", "ng-monitoring"]
            }
            // from release-8.4, no longer build tidb-binlog
            if ("${GIT_BRANCH}" >= "release-8.4") {
                releaseReposMultiArch = releaseReposMultiArch - ["tidb-binlog"]
            }
            for (item in releaseReposMultiArch) {
                def product = "${item}"
                def stageName = "${product}-multi-arch"
                if (params.NEED_MULTIARCH == "false") {
                    stageName = "${product}"
                }
                builds[stageName] = {
                    retry(2) {
                        release_one_normal(product)
                        if (product != "ng-monitoring") {
                            release_one_debug(product)
                        }
                    }
                }
            }
            failpointRepos = ["tidb", "pd", "tikv", "br", "tidb-lightning"]
            for (item_failpoint in failpointRepos) {
                def product_failpoint = "${item_failpoint}"
                builds["${item_failpoint}-failpoint"] = {
                    retry(2) {
                        release_one_enable_failpoint(product_failpoint)
                    }
                }
            }
            parallel builds
        }
        currentBuild.result = "SUCCESS"
    }
} catch (exc) {
    def sw = new StringWriter()
    def pw = new PrintWriter(sw)
    exc.printStackTrace(pw)
    echo sw.toString()
    throw exc
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
    upload_result_to_db()
    upload_pipeline_run_data()
}

def fetch_hash_version(repo, version){
    def to_sleep = false
    retry(3){
        if (to_sleep){
            sleep(time:61,unit:"SECONDS")
        }else{
            to_sleep = true
        }
        return sh(returnStdout: true, script: "python /gethash.py -repo=${repo} -version=${version}").trim()
    }
}

def fetch_hash(repo){
    return fetch_hash_version(repo, GIT_BRANCH)
}

def getHash() {
    node("gethash") {container("gethash") {
        withCredentials([string(credentialsId: 'github-token-gethash', variable: 'GHTOKEN')]) {
            tidb_sha1 = fetch_hash("tidb")
            tikv_sha1 = fetch_hash("tikv")
            pd_sha1 = fetch_hash("pd")
            if (GIT_BRANCH == 'master' || GIT_BRANCH >= "release-5.2") {
                tidb_br_sha1 = tidb_sha1
            } else {
                tidb_br_sha1 = fetch_hash("br")
            }
            // from release-8.4, no longer build tidb-binlog
            if ("${GIT_BRANCH}" == "master" || "${GIT_BRANCH}" < "release-8.4") {
                tidb_binlog_sha1 = fetch_hash("tidb-binlog")
            } else {
                tidb_binlog_sha1 = ""
            }
            tiflash_sha1 = fetch_hash("tiflash")
            cdc_sha1 = fetch_hash("tiflow")

            if (GIT_BRANCH == 'master' || GIT_BRANCH >= "release-5.3") {
                dumpling_sha1 = tidb_sha1
                dm_sha1 = cdc_sha1
                if(GIT_BRANCH == 'master'){
                    ng_monitoring_sha1 = fetch_hash_version("ng-monitoring", "main")
                }else{
                    ng_monitoring_sha1 = fetch_hash("ng-monitoring")
                }
            } else {
                dumpling_sha1 = fetch_hash("dumpling")
            }


            tidb_lightning_sha1 = tidb_br_sha1
            tidb_monitor_initializer_sha1 = fetch_hash_version("monitoring", "master")
            monitoring_sha1 = fetch_hash("monitoring")
            tiflow_sha1 = fetch_hash("tiflow")
        }
    }}
}

def upload_result_to_db() {
    pipeline_build_id = params.PIPELINE_BUILD_ID
    pipeline_id = "7"
    pipeline_name = "Nightly Image Build For QA"
    status = currentBuild.result
    build_number = BUILD_NUMBER
    job_name = JOB_NAME
    artifact_meta = "tidb commit:" + tidb_sha1 + ",tikv commit:" + tikv_sha1 + ",tiflash commit:" + tiflash_sha1 + ",dumpling commit:" + dumpling_sha1 + ",pd commit:" + pd_sha1 + ",tidb-binlog commit:" + tidb_binlog_sha1 + "ticdc commit:" + cdc_sha1 + ",dm commit:" + dm_sha1 + ",br commit:" + tidb_sha1 + ",lightning commit:" + tidb_sha1 + ",tidb-monitor-initializer commit:" + tidb_monitor_initializer_sha1 + ",ng-monitoring commit:" + ng_monitoring_sha1
    begin_time = begin_time
    end_time = new Date().format('yyyy-MM-dd HH:mm:ss')
    triggered_by = "sre-bot"
    component = "All"
    arch = "All"
    artifact_type = "community image"
    branch = params.GIT_BRANCH
    version = "None"
    build_type = "nightly-build"
    push_gcr = "No"

    build job: 'upload_result_to_db',
            wait: true,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_BUILD_ID', value: pipeline_build_id],
                    [$class: 'StringParameterValue', name: 'PIPELINE_ID', value: pipeline_id],
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: pipeline_name],
                    [$class: 'StringParameterValue', name: 'STATUS', value: status],
                    [$class: 'StringParameterValue', name: 'BUILD_NUMBER', value: build_number],
                    [$class: 'StringParameterValue', name: 'JOB_NAME', value: job_name],
                    [$class: 'StringParameterValue', name: 'ARTIFACT_META', value: artifact_meta],
                    [$class: 'StringParameterValue', name: 'BEGIN_TIME', value: begin_time],
                    [$class: 'StringParameterValue', name: 'END_TIME', value: end_time],
                    [$class: 'StringParameterValue', name: 'TRIGGERED_BY', value: triggered_by],
                    [$class: 'StringParameterValue', name: 'COMPONENT', value: component],
                    [$class: 'StringParameterValue', name: 'ARCH', value: arch],
                    [$class: 'StringParameterValue', name: 'ARTIFACT_TYPE', value: artifact_type],
                    [$class: 'StringParameterValue', name: 'BRANCH', value: branch],
                    [$class: 'StringParameterValue', name: 'VERSION', value: version],
                    [$class: 'StringParameterValue', name: 'BUILD_TYPE', value: build_type],
                    [$class: 'StringParameterValue', name: 'PUSH_GCR', value: push_gcr]
            ]

}

def upload_pipeline_run_data() {
    stage("Upload pipeline run data") {
        taskFinishTimeInMillis = System.currentTimeMillis()
        build job: 'upload-pipeline-run-data-to-db',
            wait: false,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_TYPE', value: "nightly image for internal"],
                    [$class: 'StringParameterValue', name: 'STATUS', value: currentBuild.result],
                    [$class: 'StringParameterValue', name: 'JENKINS_BUILD_ID', value: "${BUILD_NUMBER}"],
                    [$class: 'StringParameterValue', name: 'JENKINS_RUN_URL', value: "${env.RUN_DISPLAY_URL}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_REVOKER', value: "sre-bot"],
                    [$class: 'StringParameterValue', name: 'ERROR_CODE', value: "0"],
                    [$class: 'StringParameterValue', name: 'ERROR_SUMMARY', value: ""],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_START_TIME', value: "${taskStartTimeInMillis}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_END_TIME', value: "${taskFinishTimeInMillis}"],
            ]
    }
}
