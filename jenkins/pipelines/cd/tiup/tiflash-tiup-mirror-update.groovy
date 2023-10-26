def tiup_desc = ""

def tiflash_sha1, tarball_name, dir_name

def download = { name, version, os, arch ->
    if (os == "linux") {
        platform = "centos7"
    } else if (os == "darwin" && arch == "amd64") {
        platform = "darwin"
    } else if (os == "darwin" && arch == "arm64") {
        platform = "darwin-arm64"
    }  else {
        sh """
        exit 1
        """
    }

    tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${tag}/${tiflash_sha1}/${platform}/${tarball_name}
    """
}

def unpack = { name, version, os, arch ->
    tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    tar -zxf ${tarball_name}
    """
}

def pack = { name, version, os, arch ->
    sh """
    tar -czvf package/${name}-${version}-${os}-${arch}.tar.gz tiflash
    tiup mirror publish ${name} ${TIDB_VERSION} package/${name}-${version}-${os}-${arch}.tar.gz ${name}/${name} --arch ${arch} --os ${os} --desc="The TiFlash Columnar Storage Engine"
    rm -rf tiflash tiflash*.tar.gz
    """
}

def update = { name, version, os, arch ->
//    try {
    download name, version, os, arch
    unpack name, version, os, arch
    pack name, version, os, arch
//    } catch (e) {
//        echo "update ${name}-${version}-${os}-${arch}: ${e}"
//        currentBuild.result = "FAILURE"
//    }
}

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes"
    def pod_builder_image = 'hub.pingcap.net/jenkins/tiup'
    podTemplate(label: label,
            cloud: cloud,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'tiup', alwaysPullImage: true,
                            image: "${pod_builder_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                    ),
                    containerTemplate(
                            name: 'gethash', alwaysPullImage: true,
                            image: "hub.pingcap.net/jenkins/gethash", ttyEnabled: true,
                            command: '/bin/sh -c', args: 'cat',
                    )
            ],
    ) {
        node(label){
            container("tiup"){
                println "debug command:\nkubectl exec -ti ${NODE_NAME} bash"
                withCredentials([file(credentialsId: 'tiup-prod-key', variable: 'TIUPKEY_JSON')]) {
                    sh 'set +x;curl https://tiup-mirrors.pingcap.com/root.json -o /root/.tiup/bin/root.json; mkdir -p /root/.tiup/keys; cp $TIUPKEY_JSON  /root/.tiup/keys/private.json'
                    body()
                }
            }
        }
    }
}

run_with_pod {
        stage("Prepare") {
            deleteDir()
        }

        if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v3.1") {
            stage("Get hash") {
                container("gethash"){
                    tag = RELEASE_TAG
                    if(ORIGIN_TAG != "") {
                        tiflash_sha1 = ORIGIN_TAG
                    } else {
                        withCredentials([string(credentialsId: 'github-token-gethash', variable: 'GHTOKEN')]) {
                            tiflash_sha1 = sh(returnStdout: true, script: "python3 /gethash.py -repo=tics -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                        }
                    }
                }
            }


            if (params.ARCH_X86) {
                stage("tiup release tiflash linux amd64") {
                    update "tiflash", RELEASE_TAG, "linux", "amd64"
                }
            }
            if (params.ARCH_ARM) {
                stage("tiup release tiflash linux arm64") {
                    update "tiflash", RELEASE_TAG, "linux", "arm64"
                }
            }
            if (params.ARCH_MAC) {
                stage("tiup release tiflash darwin amd64") {
                    update "tiflash", RELEASE_TAG, "darwin", "amd64"
                }
            }
            if (params.ARCH_MAC_ARM && RELEASE_TAG >="v5.1.0") {
                stage("tiup release tiflash darwin arm64") {
                    update "tiflash", RELEASE_TAG, "darwin", "arm64"
                }
            }

            // upload "package"
        }
}
