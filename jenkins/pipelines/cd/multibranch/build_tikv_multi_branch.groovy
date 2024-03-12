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
def slackcolor = 'good'

def upload_result_to_db() {
    pipeline_build_id = params.PIPELINE_BUILD_ID
    pipeline_id = "3"
    pipeline_name = "TiKV"
    status = currentBuild.result
    build_number = BUILD_NUMBER
    job_name = JOB_NAME
    artifact_meta = "tikv commit:" + githash
    begin_time = begin_time
    end_time = new Date().format('yyyy-MM-dd HH:mm:ss')
    triggered_by = "sre-bot"
    component = "tikv"
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

try {
    node("build_go1210") { 
        stage('Prepare') {
                def ws = pwd()
                dir("/home/jenkins/agent/code-archive") {
                    container("golang") {
                        if(fileExists("/nfs/cache/git/src-tikv.tar.gz")){
                            timeout(5) {
                                sh """
                                cp -R /nfs/cache/git/src-tikv.tar.gz*  ./
                                mkdir -p ${ws}/tikv
                                tar -xzf src-tikv.tar.gz -C ${ws}/tikv --strip-components=1
                            """
                            }
                        }
                    }
                    dir("${ws}/tikv") {
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
                                                                url: 'git@github.com:tikv/tikv.git']]
                                    ]
                        } else {
                            checkout scm: [$class: 'GitSCM', 
                                branches: [[name: branch]],  
                                extensions: [[$class: 'LocalBranch']],
                                userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:tikv/tikv.git']]]
                        }

                        githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                    }
                }        
            }

            stage('Build') {
                def builds = [:]

                builds["linux-amd64"] = {
                    def binary = "builds/pingcap/tikv/${githash}/centos7/tikv-server.tar.gz"
                    echo "release linux amd64 binary: ${FILE_SERVER_URL}/download/${binary}"
                    def paramsBuild = [
                        string(name: "ARCH", value: "amd64"),
                        string(name: "OS", value: "linux"),
                        string(name: "EDITION", value: "community"),
                        string(name: "OUTPUT_BINARY", value: binary),
                        string(name: "REPO", value: "tikv"),
                        string(name: "PRODUCT", value: "tikv"),
                        string(name: "GIT_HASH", value: githash),
                        string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
                        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
                    ]
                    build job: "build-common",
                            wait: true,
                            parameters: paramsBuild
                }
                builds["linux-arm64"] = {
                    def binary = "builds/pingcap/tikv/${githash}/centos7/tikv-server-linux-arm64.tar.gz"
                    echo "release linux arm64 binary: ${FILE_SERVER_URL}/download/${binary}"
                    def paramsBuild = [
                        string(name: "ARCH", value: "arm64"),
                        string(name: "OS", value: "linux"),
                        string(name: "EDITION", value: "community"),
                        string(name: "OUTPUT_BINARY", value: binary),
                        string(name: "REPO", value: "tikv"),
                        string(name: "PRODUCT", value: "tikv"),
                        string(name: "GIT_HASH", value: githash),
                        string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
                        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
                    ]
                    build job: "build-common",
                            wait: true,
                            parameters: paramsBuild
                }
                builds["linux-amd64_failpoint"] = {
                    def binary = "builds/pingcap/tikv/${githash}/centos7/tikv-server-failpoint.tar.gz"
                    echo "release linux amd64 binary(enable failpoint): ${FILE_SERVER_URL}/download/${binary}"
                    def paramsBuild = [
                        string(name: "ARCH", value: "amd64"),
                        string(name: "OS", value: "linux"),
                        string(name: "EDITION", value: "community"),
                        string(name: "OUTPUT_BINARY", value: binary),
                        string(name: "REPO", value: "tikv"),
                        string(name: "PRODUCT", value: "tikv"),
                        string(name: "GIT_HASH", value: githash),
                        string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
                        [$class: 'BooleanParameterValue', name: 'FAILPOINT', value: true],
                        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
                    ]
                    build job: "build-common",
                            wait: true,
                            parameters: paramsBuild
                }
                builds.failFast = true
                parallel builds
            }

            stage("Upload") {
                dir("tikv") {
                    def refspath = "refs/pingcap/tikv/${env.BRANCH_NAME}/sha1"
                    sh """
                    echo "${githash}" > sha1
                    curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                    """
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
