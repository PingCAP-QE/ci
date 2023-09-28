def ticdc_sha1, platform, tag
def cdc_desc = "CDC is a change data capture tool for TiDB"

def download = { name, hash, os, arch ->
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

    sh """
    wget ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${tag}/${hash}/${platform}/${name}-${os}-${arch}.tar.gz
    """
}

def unpack = { name, os, arch ->
    sh """
    tar -zxf ${name}-${os}-${arch}.tar.gz
    """
}

def pack = { name, version, os, arch ->

    sh """
    # tiup package cdc -C ${name}-${os}-${arch}/bin --hide --name=cdc --release=${version} --entry=cdc --os=${os} --arch=${arch} --desc="${cdc_desc}"
    [ -d package ] || mkdir package
    tar -C bin -czvf package/cdc-${version}-${os}-${arch}.tar.gz cdc
    tiup mirror publish cdc ${TIDB_VERSION} package/cdc-${version}-${os}-${arch}.tar.gz cdc --arch ${arch} --os ${os} --desc="${cdc_desc}"
    """
}

def update = { name, version, hash, os, arch ->
    download name, hash, os, arch
    unpack name, os, arch
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

    if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
        stage("Get hash") {
            tag = RELEASE_TAG
            if(ORIGIN_TAG != "") {
                ticdc_sha1 = ORIGIN_TAG
            } else {
                container("gethash"){
                    withCredentials([string(credentialsId: 'github-token-gethash', variable: 'GHTOKEN')]) {
                        ticdc_sha1 = sh(returnStdout: true, script: "python /gethash.py -repo=tiflow -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                    }
                }
            }
        }
    }

    if (params.ARCH_X86) {
        stage("TiUP build cdc on linux/amd64") {
            update "ticdc", RELEASE_TAG, ticdc_sha1, "linux", "amd64"
        }
    }
    if (params.ARCH_ARM) {
        stage("TiUP build cdc on linux/arm64") {
            update "ticdc", RELEASE_TAG, ticdc_sha1, "linux", "arm64"
        }
    }
    if (params.ARCH_MAC) {
        stage("TiUP build cdc on darwin/amd64") {
            update "ticdc", RELEASE_TAG, ticdc_sha1, "darwin", "amd64"
        }
    }
    if (params.ARCH_MAC_ARM && RELEASE_TAG >="v5.1.0") {
        stage("TiUP build cdc on darwin/arm64") {
            update "ticdc", RELEASE_TAG, ticdc_sha1, "darwin", "arm64"
        }
    }
}
