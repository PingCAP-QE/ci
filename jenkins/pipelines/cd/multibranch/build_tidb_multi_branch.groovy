def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = isBranchMatched(["master", "release-5.1"], env.BRANCH_NAME)
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"


def BUILD_URL = 'git@github.com:pingcap/tidb.git'

def build_path = 'go/src/github.com/pingcap/tidb'
def slackcolor = 'good'
def githash
def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
def plugin_branch = branch

try {
    node("${GO_BUILD_SLAVE}") {
        def ws = pwd()

        stage("Debug Info"){
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
        }
        
        stage("Checkout") {
            dir(build_path) {
                deleteDir()
                // 如果不是 TAG，直接传 branch 给下面的 checkout 语句； 否则就应该 checkout 到 refs/tags 下 .
                // 值得注意的是，即使传入的是 TAG，环境变量里的 BRANCH_NAME 和 TAG_NAME 同时会是 TAG 名，如 v3.0.0
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
            dir(build_path) {
                container("golang") {
                    timeout(20) {
                        sh """
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        GOPATH=${ws}/go WITH_RACE=1 make && mv bin/tidb-server bin/tidb-server-race
                        GOPATH=${ws}/go WITH_CHECK=1 make && mv bin/tidb-server bin/tidb-server-check
                        GOPATH=${ws}/go make failpoint-enable && make server && mv bin/tidb-server{,-failpoint} && make failpoint-disable
                        GOPATH=${ws}/go make server_coverage || true
                        git checkout .
                        GOPATH=${ws}/go make

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

        stage("Upload") {
            dir(build_path) {
                def refspath = "refs/pingcap/tidb/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/tidb/${githash}/centos7/tidb-server.tar.gz"
                container("golang") {
                    timeout(10) {
                        sh """
                        tar --exclude=tidb-server.tar.gz -czvf tidb-server.tar.gz *
                        curl -F ${filepath}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload
                        echo "${githash}" > sha1
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
        
        stage ("Build plugins") {
            if (branch != "release-2.0" && branch != "release-2.1" && !branch.startsWith("refs/tags/v2")) {
                dir("go/src/github.com/pingcap/tidb-build-plugin") {
                    deleteDir()
                    container("golang") {
                        timeout(20) {
                            // checkout scm: [$class: 'GitSCM', 
                            // branches: [[name: branch]],  
                            // extensions: [[$class: 'LocalBranch']],
                            // userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tidb.git']]]
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
                def filepath_bytidb_whitelist = "builds/pingcap/tidb-plugins/bytidb/${githash}/centos7/whitelist-1.so"
                def md5path_whitelist = "builds/pingcap/tidb-plugins/${env.BRANCH_NAME}/centos7/whitelist-1.so.md5"
                def filepath_audit = "builds/pingcap/tidb-plugins/${env.BRANCH_NAME}/centos7/audit-1.so"
                def filepath_bytidb_audit = "builds/pingcap/tidb-plugins/bytidb/${githash}/centos7/audit-1.so"
                def md5path_audit = "builds/pingcap/tidb-plugins/${env.BRANCH_NAME}/centos7/audit-1.so.md5"

                container("golang") {
                    dir("go/src/github.com/pingcap/enterprise-plugin") {

                        if (plugin_branch.startsWith("refs/tags/v3.0")){
                            plugin_branch = "release-3.0"
                        }

                        if (plugin_branch.startsWith("refs/tags/v3.1")){
                            plugin_branch = "release-3.1"
                        }

                        if (plugin_branch.startsWith("refs/tags/v4.0")){
                            plugin_branch = "release-4.0"
                        }


                        if (plugin_branch.startsWith("release-3.0")){
                            plugin_branch = "release-3.0"
                        }
                        println plugin_branch
                         git credentialsId: 'github-sre-bot-ssh', url: "git@github.com:pingcap/enterprise-plugin.git", branch: plugin_branch
                    }
                    dir("go/src/github.com/pingcap/enterprise-plugin/whitelist") {
                        
                            sh """
                            go mod tidy
                            GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg  -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist
                            md5sum whitelist-1.so > whitelist-1.so.md5
                            curl -F ${md5path_whitelist}=@whitelist-1.so.md5 ${FILE_SERVER_URL}/upload
                            curl -F ${filepath_whitelist}=@whitelist-1.so ${FILE_SERVER_URL}/upload
                            curl -F ${filepath_bytidb_whitelist}=@whitelist-1.so ${FILE_SERVER_URL}/upload
                            """
                    }

                    dir("go/src/github.com/pingcap/enterprise-plugin/audit") {
                        sh """
                        go mod tidy
                        GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg  -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit
                        md5sum audit-1.so > audit-1.so.md5
                        curl -F ${md5path_audit}=@audit-1.so.md5 ${FILE_SERVER_URL}/upload
                        curl -F ${filepath_audit}=@audit-1.so ${FILE_SERVER_URL}/upload
                        curl -F ${filepath_bytidb_audit}=@audit-1.so ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }else{
                println "skipped plugin"
            }
        }



    }
    
    stage("Push tidb Docker") {
        // if (env.BRANCH_NAME == "master"){
            build job: 'build_image_hash', wait: false, parameters: [[$class: 'StringParameterValue', name: 'REPO', value: "tidb"], [$class: 'StringParameterValue', name: 'COMMIT_ID', value: githash], [$class: 'StringParameterValue', name: 'IMAGE_TAG', value: env.BRANCH_NAME]]
            //build job: 'pr_trigger', wait: false, parameters: [[$class: 'StringParameterValue', name: 'BUILD_BRANCH', value: "master"]]
        // }
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}

stage('Summary') {
    echo "Send slack here ..."
    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}\n @here"
    if (currentBuild.result != "SUCCESS" && (branch == "master" || branch.startsWith("release") || branch.startsWith("refs/tags/v"))) {
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}