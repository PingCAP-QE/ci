/*
* @RELEASE_TAG br-v1.0.0/cdc-v1.0.0/gc-worker-v1.0.0
*/

def tikv_migration_repo_url = "git@github.com:tikv/migration.git"
def GIT_CREDENTIAL_ID = "github-sre-bot-ssh"

def update = { os, arch ->
    sh """
    prod_dir=\${RELEASE_TAG%-v*}
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
    cd \${prod_dir}
    rm -rf \${name}*.tar.gz
    [ -d package ] || mkdir package

    tar -C bin -czvf package/\${name}-\${version}-${os}-${arch}.tar.gz \${name}
    rm -rf bin

    tiup mirror publish \${name} \${version} package/\${name}-\${version}-${os}-${arch}.tar.gz \${name} --standalone --arch ${arch} --os ${os} --desc="\${desc}"
    """
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
        stage("Clone Code") {
            script {
                // Clone and Checkout TAG
                git credentialsId: GIT_CREDENTIAL_ID, url: tikv_migration_repo_url, branch: "main"
                sh "git branch -a" // List all branches.
                sh "git tag" // List all tags.
                sh "git checkout ${RELEASE_TAG}" // Checkout to a specific tag in your repo.
                sh "ls -lart ./*"  // Just to view all the files if needed
            }
        }

        stage("Build Binary") {
            script {
                sh "go version"
                sh """
                    prod_dir=\${RELEASE_TAG%-v*}
                    cd \${prod_dir}
                    make release
                """
            }
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