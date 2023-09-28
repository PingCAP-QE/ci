def tiup_desc = ""
def br_desc = "TiDB/TiKV cluster backup restore tool"

def br_sha1, tarball_name, dir_name

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
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${tag}/${br_sha1}/${platform}/${tarball_name}
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
    rm -rf ${name}*.tar.gz
    [ -d package ] || mkdir package
    """


    sh """
    tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz br
    rm -rf bin
    """

    sh """
    tiup mirror publish ${name} ${TIDB_VERSION} package/${name}-${version}-${os}-${arch}.tar.gz ${name} --standalone --arch ${arch} --os ${os} --desc="${br_desc}"
    """
}

def update = { name, version, os, arch ->
    download name, version, os, arch
    unpack name, version, os, arch
    pack name, version, os, arch
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

    if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v3.1.0") {
        stage("Get hash") {
            tag = RELEASE_TAG
            if(ORIGIN_TAG != "") {
                br_sha1 = ORIGIN_TAG
            } else {
                container("gethash"){
                    withCredentials([string(credentialsId: 'github-token-gethash', variable: 'GHTOKEN')]) {
                        if (RELEASE_TAG >= "v5.2.0") {
                            br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                        } else {
                            br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                        }
                    }
                }
            }
        }

        if (params.ARCH_X86) {
            stage("tiup release br linux amd64") {
                update "br", RELEASE_TAG, "linux", "amd64"
            }
        }
        if (params.ARCH_ARM) {
            stage("tiup release br linux arm64") {
                update "br", RELEASE_TAG, "linux", "arm64"
            }
        }
        if (params.ARCH_MAC) {
            stage("tiup release br darwin amd64") {
                update "br", RELEASE_TAG, "darwin", "amd64"
            }
        }
        if (params.ARCH_MAC_ARM) {
            stage("tiup release br darwin arm64") {
                update "br", RELEASE_TAG, "darwin", "arm64"
            }
        }
    }
}
