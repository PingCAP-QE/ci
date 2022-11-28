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

try {
    node('build_go1190') {
        stage("Params") {
            deleteDir()
            // 如果不是 TAG，直接传 branch 给下面的 checkout 语句； 否则就应该 checkout 到 refs/tags 下 .
            // 值得注意的是，即使传入的是 TAG，环境变量里的 BRANCH_NAME 和 TAG_NAME 同时会是 TAG 名，如 v3.0.0
            def branch = (env.TAG_NAME == null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
            println branch

            if (branch.startsWith("refs/tags")) {
                checkout changelog: false,
                        poll: true,
                        scm: [$class                           : 'GitSCM',
                              branches                         : [[name: branch]],
                              doGenerateSubmoduleConfigurations: false,
                              extensions                       : [[$class: 'CheckoutOption', timeout: 30],
                                                                  [$class: 'LocalBranch'],
                                                                  [$class: 'CloneOption', noTags: true, timeout: 60]],
                              submoduleCfg                     : [],
                              userRemoteConfigs                : [[credentialsId: 'github-sre-bot-ssh',
                                                                   refspec      : "+${branch}:${branch}",
                                                                   url          : "${BUILD_URL}"]]
                        ]
            } else {
                checkout scm: [$class           : 'GitSCM',
                               branches         : [[name: branch]],
                               extensions       : [[$class: 'LocalBranch']],
                               userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}"]]]
            }

            githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            env.RELEASE_TAG = sh(returnStdout: true, script: "git describe --tags --dirty --always").trim()
            sh """
                git branch
                """
            echo githash
            echo env.RELEASE_TAG

        }
        stage("Build multiplatform") {
            builds = [:]
            builds["amd64"] = {
                def paramsBuild = [
                        string(name: "ARCH", value: "amd64"),
                        string(name: "OS", value: "linux"),
                        string(name: "EDITION", value: "community"),
                        string(name: "OUTPUT_BINARY", value: "builds/pingcap/pd/${env.BRANCH_NAME}/${githash}/centos7/pd-server.tar.gz"), // why different with arm?
                        string(name: "REPO", value: 'pd'),
                        string(name: "PRODUCT", value: 'pd'),
                        string(name: "GIT_HASH", value: githash),
                        string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
                        string(name: "RELEASE_TAG", value: env.RELEASE_TAG),
                        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
                ]
                echo "${paramsBuild}"
                build job: "build-common",
                        wait: true,
                        parameters: paramsBuild
            }
            builds["arm64"] = {
                def paramsBuild = [
                        string(name: "ARCH", value: "arm64"),
                        string(name: "OS", value: "linux"),
                        string(name: "EDITION", value: "community"),
                        string(name: "OUTPUT_BINARY", value: "builds/pingcap/test/pd/${githash}/centos7/pd-linux-arm64.tar.gz"), // why test?
                        string(name: "REPO", value: 'pd'),
                        string(name: "PRODUCT", value: 'pd'),
                        string(name: "GIT_HASH", value: githash),
                        string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
                        string(name: "RELEASE_TAG", value: env.RELEASE_TAG),
                        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
                ]
                echo "${paramsBuild}"
                build job: "build-common",
                        wait: true,
                        parameters: paramsBuild
            }
            parallel builds

        }


        stage("Upload") {
            dir("go/src/github.com/pingcap/pd") {
                def refspath = "refs/pingcap/pd/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/pd/${env.BRANCH_NAME}/${githash}/centos7/pd-server.tar.gz"
                def filepath2 = "builds/pingcap/pd/${githash}/centos7/pd-server.tar.gz" // why ?
                container("golang") {
                    timeout(10) {
                        sh """
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
    echo "${e}"
} finally {
    if (env.BRANCH_NAME == 'master') {
        upload_result_to_db()
    }

}
