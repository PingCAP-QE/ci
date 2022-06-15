/*
* @RELEASE_TAG
* @VERSION
* @PROD_NAME // tikv-br, tikv-cdc, gc-worker
*/

def br_desc = "TiKV cluster backup restore tool"
def tikv_migration_repo_url = "git@github.com:tikv/migration.git"
def GIT_CREDENTIAL_ID = "github-sre-bot-ssh"
def dir = ""
if (PROD_NAME == "tikv-br") {
    dir = "br"
} else if (PROD_NAME == "tikv-cdc") {
    dir = "cdc"
} else if (PROD_NAME == "gc-worker") {
    dir = "gc-worker"
}

def update = { name, version, os, arch ->
    sh """
    cd ${dir}
    rm -rf ${name}*.tar.gz
    [ -d package ] || mkdir package

    tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz tikv-br
    rm -rf bin

    tiup mirror publish ${name} ${version} package/${name}-${version}-${os}-${arch}.tar.gz ${name} --standalone --arch ${arch} --os ${os} --desc="${br_desc}"
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
                    cd ${dir}
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
            stage("tiup release br linux amd64") {
                update PROD_NAME, RELEASE_TAG, "linux", "amd64"
            }
        }
        if (params.ARCH_ARM) {
            stage("tiup release br linux arm64") {
                update PROD_NAME, RELEASE_TAG, "linux", "arm64"
            }
        }
        if (params.ARCH_MAC) {
            stage("tiup release br darwin amd64") {
                update PROD_NAME, RELEASE_TAG, "darwin", "amd64"
            }
        }
        if (params.ARCH_MAC_ARM) {
            stage("tiup release br darwin arm64") {
                update PROD_NAME, RELEASE_TAG, "darwin", "arm64"
            }
        }
    }
}