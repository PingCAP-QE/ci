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

def upload_result_to_db() {
    pipeline_build_id = params.PIPELINE_BUILD_ID
    pipeline_id = "4"
    pipeline_name = "PD"
    status = currentBuild.result
    build_number = BUILD_NUMBER
    job_name = JOB_NAME
    artifact_meta = "pd commit:" + githash
    begin_time = begin_time
    end_time = new Date().format('yyyy-MM-dd HH:mm:ss')
    triggered_by = "sre-bot"
    component = "pd"
    arch = "linux-amd64"
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

def BUILD_URL = 'git@github.com:tikv/pd.git'
def slackcolor = 'good'
def master_branch_node = "${GO_BUILD_SLAVE}"
def branchNodeMap = [
    "master" : master_branch_node,
    "release-2.0" : "build_go1130",
    "release-2.1" : "build_go1130",
    "release-3.0" : "build_go1130",
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
        string(name: "RELEASE_TAG", value: env.BRANCH_NAME),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
    ]
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}


try {
    // 如果不在 map 里，如 release-3.1 分支或者 tag 分支，就使用和 master 一样的环境
    node(branchNodeMap.get("${env.BRANCH_NAME}".toString(), master_branch_node)) {
        def ws = pwd()
        //deleteDir()

        stage("Debug Info"){
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
        }

        stage("Checkout") {
            dir("go/src/github.com/pingcap/pd") {
                deleteDir()
                // 如果不是 TAG，直接传 branch 给下面的 checkout 语句； 否则就应该 checkout 到 refs/tags 下 .
                // 值得注意的是，即使传入的是 TAG，环境变量里的 BRANCH_NAME 和 TAG_NAME 同时会是 TAG 名，如 v3.0.0
                def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
                println branch
                
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
                                                        url: "${BUILD_URL}"]]
                            ]
                } else {
                    checkout scm: [$class: 'GitSCM', 
                        branches: [[name: branch]],  
                        extensions: [[$class: 'LocalBranch']],
                        userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}"]]]
                }
                
                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                
                sh"""
                git branch
                """
            }
        }

        stage("Build") {
            dir("go/src/github.com/pingcap/pd") {
                container("golang") {
                    timeout(10) {
                        sh """
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        GOPATH=${ws}/go make
                        GOPATH=${ws}/go make tools
                        """
                    }
                }
            }
        }

        stage("Upload") {
            dir("go/src/github.com/pingcap/pd") {
                def refspath = "refs/pingcap/pd/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/pd/${env.BRANCH_NAME}/${githash}/centos7/pd-server.tar.gz"
                def filepath2 = "builds/pingcap/pd/${githash}/centos7/pd-server.tar.gz"
                container("golang") {
                    release_one("pd","${githash}")
                    timeout(10) {
                        sh """
                        tar --exclude=pd-server.tar.gz -czvf pd-server.tar.gz *
                        curl -F ${filepath}=@pd-server.tar.gz ${FILE_SERVER_URL}/upload
                        curl -F ${filepath2}=@pd-server.tar.gz ${FILE_SERVER_URL}/upload

                        echo "${githash}" > sha1
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
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
}finally{
    if(env.BRANCH_NAME == 'master'){
         upload_result_to_db()
    }
   
}

stage('Summary') {
    echo "Send slack here ..."
    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}"
    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}