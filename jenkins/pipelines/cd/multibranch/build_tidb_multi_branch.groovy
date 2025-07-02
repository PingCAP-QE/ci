properties([
        parameters([
                string(
                        defaultValue: '-1',
                        name: 'PIPELINE_BUILD_ID',
                        description: '',
                        trim: true
                )
     ])
])

begin_time = new Date().format('yyyy-MM-dd HH:mm:ss')
githash = ""

@NonCPS
boolean isMoreRecentOrEqual( String a, String b ) {
    if (a == b) {
        return true
    }

    [a,b]*.tokenize('.')*.collect { it as int }.with { u, v ->
       Integer result = [u,v].transpose().findResult{ x,y -> x <=> y ?: null } ?: u.size() <=> v.size()
       return (result == 1)
    }
}

string trimPrefix = {
    it.startsWith('release-') ? it.minus('release-') : it
}

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

// choose which go version to use.
def selectGoVersion(branchNameOrTag) {
    if (branchNameOrTag.startsWith("v")) {
        println "This is a tag"
        // Handle v9.0.0-beta.n tags
        if (branchNameOrTag.startsWith("v9.0.0-beta")) {
            println "tag ${branchNameOrTag} is beta release, use go 1.23"
            return "go1.23"
        }
        if (branchNameOrTag >= "v8.4") {
            println "tag ${branchNameOrTag} use go 1.23"
            return "go1.23"
        }
        if (branchNameOrTag >= "v7.4") {
            println "tag ${branchNameOrTag} use go 1.21"
            return "go1.21"
        }
        if (branchNameOrTag >= "v7.0") {
            println "tag ${branchNameOrTag} use go 1.20"
            return "go1.20"
        }
        // special for v6.1 larger than patch 3
        if (branchNameOrTag.startsWith("v6.1") && branchNameOrTag >= "v6.1.3" || branchNameOrTag=="v6.1.0-nightly") {
            return "go1.19"
        }
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
        println "tag ${branchNameOrTag} use default version go 1.23"
        return "go1.23"
    } else {
        println "this is a branch"
        if (branchNameOrTag == "master") {
            println("branchNameOrTag: master  use go1.23")
            return "go1.23"
        }
        // Handle release-9.0-beta branches
        if (branchNameOrTag.startsWith("release-9.0-beta")) {
            println("branchNameOrTag: ${branchNameOrTag} is beta release, use go1.23")
            return "go1.23"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag >= "release-8.4") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.23")
            return "go1.23"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag >= "release-7.4") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.21")
            return "go1.21"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag >= "release-7.0") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.20")
            return "go1.20"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-7.0" && branchNameOrTag >= "release-6.1") {
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
        println "branchNameOrTag: ${branchNameOrTag}  use default version go1.23"
        return "go1.23"
    }
}


def GO_BUILD_SLAVE = "build_go1230"
def goVersion = selectGoVersion(env.BRANCH_NAME)
switch(goVersion) {
    case "go1.23":
        GO_BUILD_SLAVE = "build_go1230"
        break
    case "go1.21":
        GO_BUILD_SLAVE = "build_go1210"
        break
    case "go1.20":
        GO_BUILD_SLAVE = "build_go1200"
        break
    case "go1.19":
        GO_BUILD_SLAVE = "build_go1190"
        break
    case "go1.18":
        GO_BUILD_SLAVE = "build_go1180"
        break
    case "go1.16":
        GO_BUILD_SLAVE = "build_go1160"
        break
    case "go1.13":
        GO_BUILD_SLAVE = "build_go1130"
        break
    default:
        GO_BUILD_SLAVE = "build_go1210"
        break
}
println "This build use ${goVersion}"
println "This build use ${GO_BUILD_SLAVE}"


// Notice:
// br code has been merged into tidb codebase from v5.2
// dumpling code has been merged into tidb codebase from v5.3
def isNeedBuildBr = false
def isNeedBuildDumpling = false
releaseBranchBuildBr = "release-5.2"
releaseBranchBuildDumpling = "release-5.3"

// First check the master branch
if (!isNeedBuildBr) {
    isNeedBuildBr = isBranchMatched(["master"], env.BRANCH_NAME)
}
if (!isNeedBuildDumpling) {
    isNeedBuildDumpling = isBranchMatched(["master"], env.BRANCH_NAME)
}

// Add special handling for beta version branches
if (!isNeedBuildBr && env.BRANCH_NAME.startsWith("release-9.0-beta")) {
    println "Beta branch ${env.BRANCH_NAME} detected, building BR"
    isNeedBuildBr = true
}
if (!isNeedBuildDumpling && env.BRANCH_NAME.startsWith("release-9.0-beta")) {
    println "Beta branch ${env.BRANCH_NAME} detected, building Dumpling"
    isNeedBuildDumpling = true
}

// Check standard version tags
if (!isNeedBuildBr && env.BRANCH_NAME.startsWith("v") && env.BRANCH_NAME > "v5.2") {
    isNeedBuildBr = true
}
if (!isNeedBuildDumpling && env.BRANCH_NAME.startsWith("v") && env.BRANCH_NAME > "v5.3") {
    isNeedBuildDumpling = true
}

// Handling for normal release branches
if (!isNeedBuildBr && env.BRANCH_NAME.startsWith("release-") && !env.BRANCH_NAME.contains("beta")) {
    isNeedBuildBr = isMoreRecentOrEqual(trimPrefix(env.BRANCH_NAME), trimPrefix(releaseBranchBuildBr))
}
if (!isNeedBuildDumpling && env.BRANCH_NAME.startsWith("release-") && !env.BRANCH_NAME.contains("beta")) {
    isNeedBuildDumpling = isMoreRecentOrEqual(trimPrefix(env.BRANCH_NAME), trimPrefix(releaseBranchBuildDumpling))
}

def build_path = 'go/src/github.com/pingcap/tidb'
def slackcolor = 'good'
def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
def plugin_branch = branch
if (branch.startsWith("feature/")) {
    println "This is a feature branch, use master branch to build plugins"
    plugin_branch = "master"
}

def release_one(repo,product,hash,arch,binary) {
    echo "release binary: ${FILE_SERVER_URL}/download/${binary}"
    def paramsBuild = [
        string(name: "ARCH", value: arch),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: product),
        string(name: "GIT_HASH", value: hash),
        string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
    ]
    if (env.TAG_NAME != null) {
        paramsBuild.push(string(name: "RELEASE_TAG", value: env.TAG_NAME))
    }
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}

def upload_result_to_db() {
    pipeline_build_id = params.PIPELINE_BUILD_ID
    pipeline_id = "1"
    pipeline_name = "TiDB"
    status = currentBuild.result
    build_number = BUILD_NUMBER
    job_name = JOB_NAME
    artifact_meta = "tidb commit:" + githash
    begin_time = begin_time
    end_time = new Date().format('yyyy-MM-dd HH:mm:ss')
    triggered_by = "sre-bot"
    component = "tidb"
    arch = "All"
    artifact_type = "binary"
    branch = "master"
    version = "None"
    build_type = "dev-build"
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


try {
    node("${GO_BUILD_SLAVE}") {
        def ws = pwd()

        stage("Debug Info"){
            println "debug command:\nkubectl -n jenkins-cd exec -ti ${NODE_NAME} bash"
        }

        stage("Checkout") {
            dir(build_path) {
                deleteDir()
                // If not a TAG, directly pass branch to the checkout statement below; otherwise, it should check out to refs/tags.
                // Note that even if a TAG is passed, the environment variables BRANCH_NAME and TAG_NAME will both be the TAG name, such as v3.0.0
                println branch
                retry(3) {
                    if(branch.startsWith("refs/tags")) {
                        checkout changelog: false,
                                poll: true,
                                scm: [$class: 'GitSCM',
                                        branches: [[name: branch]],
                                        doGenerateSubmoduleConfigurations: false,
                                        extensions: [[$class: 'CheckoutOption', timeout: 30],
                                                    [$class: 'LocalBranch'],
                                                    [$class: 'CloneOption', noTags: true, timeout: 60]],
                                        submoduleCfg: [],
                                        userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                            refspec: "+${branch}:${branch}",
                                                            url: 'git@github.com:pingcap/tidb.git']]
                                ]
                    } else {
                        checkout scm: [$class: 'GitSCM',
                            branches: [[name: branch]],
                            extensions: [[$class: 'LocalBranch']],
                            userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tidb.git']]]
                    }
                }

                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }
        }

        stage("Build") {
            def stages = [:]
            stages["build tidb"] ={
                dir(build_path) {
                    container("golang") {
                        timeout(20) {
                            sh """
                            mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                            GOPATH=${ws}/go WITH_RACE=1 make && mv bin/tidb-server bin/tidb-server-race
                            git checkout .
                            GOPATH=${ws}/go WITH_CHECK=1 make && mv bin/tidb-server bin/tidb-server-check
                            git checkout .
                            GOPATH=${ws}/go make failpoint-enable && make server && mv bin/tidb-server{,-failpoint} && make failpoint-disable
                            git checkout .
                            GOPATH=${ws}/go make server_coverage || true
                            git checkout .
                            GOPATH=${ws}/go make
                            git checkout .

                            if [ \$(grep -E "^ddltest:" Makefile) ]; then
                                GOPATH=${ws}/go make ddltest
                            fi

                            if [ \$(grep -E "^importer:" Makefile) ]; then
                                GOPATH=${ws}/go make importer
                            fi
                            """
                        }
                    }
                }
            }
            stages["build br"] = {
                if (isNeedBuildBr) {
                    def brAmdBinary = "builds/pingcap/br/${env.BRANCH_NAME}/${githash}/centos7/br.tar.gz"
                    release_one("tidb","br","${githash}","amd64",brAmdBinary)
                }
            }
            stages["build tidb arm64"] = {
                def tidbArmBinary = "builds/pingcap/test/tidb/${githash}/centos7/tidb-linux-arm64.tar.gz"
                release_one("tidb","tidb","${githash}","arm64",tidbArmBinary)
            }
            stages["build dumpling"] = {
                if (isNeedBuildDumpling) {
                    def DumplingAmdBinary = "builds/pingcap/dumpling/${env.BRANCH_NAME}/${githash}/centos7/dumpling.tar.gz"
                    release_one("tidb","dumpling","${githash}","amd64",DumplingAmdBinary)
                    def DumplingAmdBinaryPath2 = "builds/pingcap/dumpling/${githash}/centos7/dumpling.tar.gz"
                    release_one("tidb","dumpling","${githash}","amd64",DumplingAmdBinaryPath2)
                }
            }
            parallel(stages)
        }
        stage("Upload sha1") {
            dir(build_path) {
                def refspath = "refs/pingcap/tidb/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/tidb/${env.BRANCH_NAME}/${githash}/centos7/tidb-server.tar.gz"
                def filepath2 = "builds/pingcap/tidb/${githash}/centos7/tidb-server.tar.gz"
                def patch_path = "builds/pingcap/tidb/patch/${env.BRANCH_NAME}/${githash}/centos7/tidb-server.tar.gz"
                container("golang") {
                    retry(3) {
                        timeout(10) {
                        sh """
                        tar --exclude=tidb-server.tar.gz -czvf tidb-server.tar.gz *
                        bin/tidb-server -V
                        curl --fail -F  ${filepath}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload | egrep 'success'
                        curl --fail -F  ${filepath2}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload | egrep 'success'

                        echo "${githash}" > sha1
                        curl --fail -F  ${refspath}=@sha1 ${FILE_SERVER_URL}/upload | egrep 'success'
                        """
                        }
                    }
                }
            }
        }

        stage ("Build plugins") {
            dir("go/src/github.com/pingcap/tidb-build-plugin") {
                deleteDir()
                container("golang") {
                    timeout(20) {
                        sh """
                        cp -R ${ws}/${build_path}/. ./
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        # GOPATH=${ws}/go  make
                        cd cmd/pluginpkg
                        go build
                        """
                    }
                }
            }

            def filepath_whitelist = "builds/pingcap/tidb-plugins/${env.BRANCH_NAME}/centos7/whitelist-1.so"
            def md5path_whitelist = "builds/pingcap/tidb-plugins/${env.BRANCH_NAME}/centos7/whitelist-1.so.md5"
            def filepath_audit = "builds/pingcap/tidb-plugins/${env.BRANCH_NAME}/centos7/audit-1.so"
            def md5path_audit = "builds/pingcap/tidb-plugins/${env.BRANCH_NAME}/centos7/audit-1.so.md5"

            container("golang") {
                dir("go/src/github.com/pingcap/enterprise-plugin") {
                    println "old enterprise-plugin branch: ${plugin_branch}"
                    git credentialsId: 'github-sre-bot-ssh', url: "git@github.com:pingcap/enterprise-plugin.git", branch: plugin_branch
                }
                dir("go/src/github.com/pingcap/enterprise-plugin/whitelist") {
                        sh """
                        go mod tidy
                        GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg  -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist
                        md5sum whitelist-1.so > whitelist-1.so.md5
                        curl -F ${md5path_whitelist}=@whitelist-1.so.md5 ${FILE_SERVER_URL}/upload
                        curl -F ${filepath_whitelist}=@whitelist-1.so ${FILE_SERVER_URL}/upload
                        """
                }
                dir("go/src/github.com/pingcap/enterprise-plugin/audit") {
                    sh """
                    go mod tidy
                    GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg  -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit
                    md5sum audit-1.so > audit-1.so.md5
                    curl -F ${md5path_audit}=@audit-1.so.md5 ${FILE_SERVER_URL}/upload
                    curl -F ${filepath_audit}=@audit-1.so ${FILE_SERVER_URL}/upload
                    """
                }
            }
        }
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}finally{
    if(env.BRANCH_NAME == 'master'){
         upload_result_to_db()
    }
}
