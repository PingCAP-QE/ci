// choose which go version to use. 
def selectGoVersion(branchNameOrTag) {
    if (branchNameOrTag.startsWith("v")) {
        println "This is a tag"
        if (branchNameOrTag >= "v6.3") {
            println "tag ${branchNameOrTag} use go 1.19"
            return "go1.19"
        }
        if (branchNameOrTag >= "v6.0") {
            println "tag ${branchNameOrTag} use go 1.18"
            return "go1.18"
        }
        if (branchNameOrTag >= "v5.1") {
            println "tag ${branchNameOrTag} use go 1.16"
            return "go1.16"
        }
        if (branchNameOrTag < "v5.1") {
            println "tag ${branchNameOrTag} use go 1.13"
            return "go1.13"
        }
        println "tag ${branchNameOrTag} use default version go 1.19"
        return "go1.19"
    } else { 
        println "this is a branch"
        if (branchNameOrTag == "master") {
            println("branchNameOrTag: master  use go1.19")
            return "go1.19"
        }


        if (branchNameOrTag.startsWith("release-") && branchNameOrTag >= "release-6.1") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.19")
            return "go1.19"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-6.1"  && branchNameOrTag >= "release-6.0") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.18")
            return "go1.18"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-6.0" && branchNameOrTag >= "release-5.1") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.16")
            return "go1.16"
        }

        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-5.1") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.13")
            return "go1.13"
        }
        println "branchNameOrTag: ${branchNameOrTag}  use default version go1.18"
        return "go1.19"
    }
}


def GO_BUILD_SLAVE = "build_go1190"
def goVersion = selectGoVersion(env.BRANCH_NAME)
if ( goVersion == "go1.18" ) {
    GO_BUILD_SLAVE = "build_go1180"
}
if ( goVersion == "go1.16" ) {
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
}
if ( goVersion == "go1.13" ) {
    GO_BUILD_SLAVE = "build_go1130_memvolume"
}

println "This build use ${goVersion}"


def BUILD_URL = 'git@github.com:pingcap/tidb-test.git'
def slackcolor = 'good'
def githash
def master_branch_node = "${GO_BUILD_SLAVE}"
def branchNodeMap = [
    "master" : master_branch_node,
    "release-2.0" : "build_go1120",
    "release-2.1" : "build_go1120"
]


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
        string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
    ]
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}


try {
    // 如果不在 map 里，如 release-3.0 分支或者 tag 分支，就使用和 master 一样的环境
    node(branchNodeMap.get("${env.BRANCH_NAME}".toString(), master_branch_node)) {
        def ws = pwd()
        //deleteDir()
        stage("Debug Info"){
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
        }
        stage("Checkout") {
            dir("go/src/github.com/pingcap/tidb-test") {
                // 如果不是 TAG，直接传 branch 给下面的 checkout 语句； 否则就应该 checkout 到 refs/tags 下 .
                // 值得注意的是，即使传入的是 TAG，环境变量里的 BRANCH_NAME 和 TAG_NAME 同时会是 TAG 名，如 v3.0.0
                def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"

                git credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}", branch: "${branch}"
                
                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }
        }

        stage("Build") {
            dir("go/src/github.com/pingcap/tidb-test") {
                container("golang") {
                    for (binCase in ['partition_test', 'coprocessor_test', 'concurrent-sql']) {
                        if (fileExists("${binCase}/build.sh")) { dir(binCase) { sh "bash build.sh" } }
                    }
                }
            }
        }

        stage("Upload") {
            dir("go/src/github.com/pingcap/tidb-test") {
                def refspath = "refs/pingcap/tidb-test/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/tidb-test/${githash}/centos7/tidb-test.tar.gz"
                container("golang") {
                    release_one("tidb-test","${githash}")
                    timeout(10) {
                        sh """
                        tar --exclude=tidb-test.tar.gz -czvf tidb-test.tar.gz ./*
                        curl --fail -F ${filepath}=@tidb-test.tar.gz ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                        echo "${githash}" > sha1
                        curl --fail -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
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

stage('Summary') {
    echo "Send slack here ..."
    //slackSend channel: "", color: "${slackcolor}", teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
}