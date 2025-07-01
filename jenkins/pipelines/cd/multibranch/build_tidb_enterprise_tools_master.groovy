// choose which go version to use.
def String selectGoVersion(String branchORTag) {
    def goVersion="go1.18"
    if (branchORTag.startsWith("v") && branchORTag <= "v5.1") {
        return "go1.13"
    }
    if (branchORTag.startsWith("v") && branchORTag > "v5.1" && branchORTag < "v6.0") {
        return "go1.16"
    }
    if (branchORTag.startsWith("release-") && branchORTag < "release-5.1"){
        return "go1.13"
    }
    if (branchORTag.startsWith("release-") && branchORTag >= "release-5.1" && branchORTag < "release-6.0"){
        return "go1.16"
    }
    if (branchORTag.startsWith("hz-poc") || branchORTag.startsWith("arm-dup") ) {
        return "go1.16"
    }
    return "go1.18"
}


def GO_BUILD_SLAVE = GO1180_BUILD_SLAVE
def goVersion = selectGoVersion(env.BRANCH_NAME)
if ( goVersion == "go1.16" ) {
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
}
if ( goVersion == "go1.13" ) {
    GO_BUILD_SLAVE = GO_BUILD_SLAVE
}

println "This build use ${goVersion}"

def BUILD_URL = 'git@github.com:pingcap/tidb-enterprise-tools.git'
def githash_centos7

def release_one(repo,hash) {
    def binary = "builds/pingcap/test/${repo}/${hash}/centos7/${repo}-linux-arm64.tar.gz"
    echo "release binary: ${FILE_SERVER_URL}/download/${binary}"
    def paramsBuild = [
        string(name: "ARCH", value: "arm64"),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: repo),
        string(name: "GIT_HASH", value: hash),
        string(name: "TARGET_BRANCH", value: BUILD_BRANCH),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
    ]
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}

try {
    def buildSlave = "${GO_BUILD_SLAVE}"

    node(buildSlave) {
        def ws = pwd()
        deleteDir()

        stage("Checkout") {
            dir("go/src/github.com/pingcap/tidb-enterprise-tools") {
                git credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}", branch: "${BUILD_BRANCH}"
                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }
        }

        stage("Build") {
            dir("go/src/github.com/pingcap/tidb-enterprise-tools") {
                container("golang") {
                    timeout(10) {
                        sh """
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        GOPATH=${ws}/go make syncer
                        GOPATH=${ws}/go make loader
                        """
                    }
                }
            }
        }

        stage("Upload") {
            dir("go/src/github.com/pingcap/tidb-enterprise-tools") {
                def refspath = "refs/pingcap/tidb-enterprise-tools/${BUILD_BRANCH}/sha1"
                def filepath = "builds/pingcap/tidb-enterprise-tools/${githash}/centos7/tidb-enterprise-tools.tar.gz"
                container("golang") {
                    release_one("tidb-enterprise-tools","${githash}")
                    timeout(10) {
                        sh """
                        echo "${githash}" > sha1
                        curl --fail -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload | egrep 'success'
                        tar czvf tidb-enterprise-tools.tar.gz bin/*
                        curl --fail -F ${filepath}=@tidb-enterprise-tools.tar.gz ${FILE_SERVER_URL}/upload | egrep 'success'
                        """
                    }
                }
            }
        }
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}
