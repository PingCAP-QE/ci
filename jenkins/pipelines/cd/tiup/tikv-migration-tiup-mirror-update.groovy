/*
* @ RELEASE_TAG br-v1.0.0/cdc-v1.0.0/gc-worker-v1.0.0
* @ STAGING_SERVER false/true
*/

def tikv_migration_repo_url = "https://github.com/tikv/migration"
def mirror_server = "http://staging.tiup-server.pingcap.net"
if (!params.STAGING_SERVER) {
    mirror_server = "http://prod.tiup-server.pingcap.net"
}

def download = { os, arch ->
    sh """
    name=tikv-\${RELEASE_TAG%-v*}
    version=v\${RELEASE_TAG#*-v}
    [ -d package ] || mkdir package

    package_name=\${name}-\${version}-${os}-${arch}.tar.gz
    download_url=${tikv_migration_repo_url}/releases/download/${RELEASE_TAG}/\${package_name}
    wget -P package/ \${download_url}
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
    else
        echo "Unsupported production \${name}"
        exit 1
    fi

    tiup mirror set ${mirror_server}
    tiup mirror publish \${name} \${version} package/\${name}-\${version}-${os}-${arch}.tar.gz \${name} --standalone --arch ${arch} --os ${os} --desc="\${desc}"
    """
}

def update = { os, arch ->
    download os, arch
    publish os, arch
}

def GO_BUILD_SLAVE = GO1180_BUILD_SLAVE
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
