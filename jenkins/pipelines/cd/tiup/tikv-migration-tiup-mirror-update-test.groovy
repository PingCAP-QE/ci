/*
* @RELEASE_TAG br-v1.0.0/cdc-v1.0.0/gc-worker-v1.0.0
*/

def tikv_migration_repo_url = "https://github.com/tikv/migration"

def download = { os, arch ->
    sh """
    name=tikv-\${RELEASE_TAG%-v*}
    version=v\${RELEASE_TAG#*-v}
    [ -d package ] || mkdir package

    package_name=\${name}-\${version}-${os}-${arch}.tar.gz
    download_url=${tikv_migration_repo_url}/releases/download/${RELEASE_TAG}/\${package_name}
    wget -P pakcage/ \${download_url}
    """
}

def publish = { os, arch ->
    sh """
    name=tikv-\${RELEASE_TAG%-v*}
    version=v\${RELEASE_TAG#*-v}
    desc=""
    if [ \${name} == "tikv-br" ]; then
        desc="TiKV cluster backup restore tool"
    elif [ \${name} == "tikv-cdc" ]; then
        desc="TiKV-CDC is a change data capture tool for TiKV"
    elif [ \${name} == "tikv-gc-worker" ]; then
        desc="GC Worker is a component for TiKV to control the gc process"
    fi

    tiup mirror publish \${name} \${version} package/\${name}-\${version}-${os}-${arch}.tar.gz \${name} --standalone --arch ${arch} --os ${os} --desc="\${desc}"
    """
}

def update = { os, arch ->
    download os, arch
    publish os, arch
}

def String selectGoVersion(String branchORTag) {
    return "go1.18"
}

def GO_BUILD_SLAVE = GO1180_BUILD_SLAVE
def goVersion = selectGoVersion(RELEASE_TAG)
if ( goVersion == "go1.16" ) {
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
}

node("${GO_BUILD_SLAVE}") {
    container("golang") {
        stage("Prepare") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            deleteDir()
        }

        checkout scm
        def util = load "jenkins/pipelines/cd/tiup/tiup_utils.groovy"

        stage("Install tiup") {
            util.install_tiup "/usr/local/bin", PINGCAP_PRIV_KEY
        }

        if (params.ARCH_X86) {
            stage("tiup release tikv migration linux amd64") {
                update "linux", "amd64"
            }
        }
        if (params.ARCH_ARM) {
            stage("tiup release tikv migration linux arm64") {
                update "linux", "arm64"
            }
        }
        if (params.ARCH_MAC) {
            stage("tiup release tikv migration darwin amd64") {
                update "darwin", "amd64"
            }
        }
        if (params.ARCH_MAC_ARM) {
            stage("tiup release tikv migration darwin arm64") {
                update "darwin", "arm64"
            }
        }
    }
}