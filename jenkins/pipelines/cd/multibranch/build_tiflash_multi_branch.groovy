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
def label = "build-tiflash-release"
def slackcolor = 'good'
githash = null
def upload_result_to_db() {
    pipeline_build_id = params.PIPELINE_BUILD_ID
    pipeline_id = "2"
    pipeline_name = "TiFlash"
    status = currentBuild.result
    build_number = BUILD_NUMBER
    job_name = JOB_NAME
    artifact_meta = "tiflash commit:" + githash
    begin_time = begin_time
    end_time = new Date().format('yyyy-MM-dd HH:mm:ss')
    triggered_by = "sre-bot"
    component = "tiflash"
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

stage("Get Hash") {
    node("${GO_TEST_SLAVE}") {
		container("golang"){
				def target_branch = (env.TAG_NAME == null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
				echo "Target Branch: ${target_branch}"
				sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
				githash = sh(returnStdout: true, script: "python gethash.py -repo=tics -source=github -version=${target_branch} -s=${FILE_SERVER_URL}").trim()
		}
    }
}

def release_amd64(repo, hash) {
    def filepath = "builds/pingcap/tiflash/release/${env.BRANCH_NAME}/${hash}/centos7/tiflash.tar.gz"

    if ("master" != "${env.BRANCH_NAME}") {
        filepath = "builds/pingcap/tiflash/${env.BRANCH_NAME}/${hash}/centos7/tiflash.tar.gz"
    }

    echo "release filepath: ${FILE_SERVER_URL}/download/${filepath}"

    def paramsBuild = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: filepath),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: repo),
        string(name: "GIT_HASH", value: hash),
        string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
        string(name: "USE_TIFLASH_RUST_CACHE", value: "true"),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
    ]
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}

def docker_amd64(repo, hash) {
    def filepath = "builds/pingcap/tiflash/${env.BRANCH_NAME}/${hash}/centos7/tiflash.tar.gz"

    echo "input filepath: ${FILE_SERVER_URL}/download/${filepath}"

    def release_tag = null

    if (env.TAG_NAME != null) {
        release_tag = env.TAG_NAME
    } else {
        release_tag = ""
    }
    image_tag = "${env.BRANCH_NAME}".replaceAll("/", "-")
    def paramsBuild = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: filepath),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: repo),
        string(name: "RELEASE_TAG", value: release_tag),
        string(name: "DOCKERFILE", value: "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/tiflash_ci"),
        string(name: "RELEASE_DOCKER_IMAGES", value: "hub.pingcap.net/tiflash/tiflash:${image_tag},hub.pingcap.net/tiflash/tics:${image_tag}")
    ]

    build job: "docker-common",
            wait: true,
            parameters: paramsBuild
}

try {
    stage("Nightly Build") {
        release_amd64("tics", "${githash}")
        if ("master" == "${env.BRANCH_NAME}") {
            node("${GO_TEST_SLAVE}") {
                container("golang") {
                    sh """
                    cd /tmp/
                    curl -o /tmp/tiflash.tar.gz ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/release/${env.BRANCH_NAME}/${githash}/centos7/tiflash.tar.gz
                    curl -F builds/pingcap/tiflash/${env.BRANCH_NAME}/${githash}/centos7/tiflash.tar.gz=@tiflash.tar.gz ${FILE_SERVER_URL}/upload
                    """
                }
            }
        }
    }

    stage("Docker") {
        docker_amd64("tics", "${githash}")
    }

    currentBuild.result = "SUCCESS"

    stage("Update sha1") {
        node("${GO_TEST_SLAVE}") {
            container("golang") {
                println "githash=${githash}"
                if (githash != null && !githash.isEmpty()) {
                    echo "all build success, update sha1 now..."
                    // 用 sha1 标志 branch 上最新的 commit ID，以此去获取 tarball 的路径
                    def refspath = "refs/pingcap/tics/${env.BRANCH_NAME}/sha1"
                    def refspath1 = "refs/pingcap/tiflash/${env.BRANCH_NAME}/sha1"
                    sh """
                    echo "${githash}" > sha1
                    curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                    curl -F ${refspath1}=@sha1 ${FILE_SERVER_URL}/upload
                    """
                } else {
                    echo 'invalid gitHash, mark this build as failed'
                    currentBuild.result = 'FAILURE'
                    slackcolor = 'danger'
                }
            }
        }
    }
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
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def msg = "Build Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins`" + "\n" +
            "${env.RUN_DISPLAY_URL}"

    echo "${msg}"

    if (currentBuild.result != "SUCCESS") {
        stage("sendLarkMessage") {
            def result_mark = "❌"
            def feishumsg = "build_tiflash_multi_branch\\n" +
                    "Build Number: ${env.BUILD_NUMBER}\\n" +
                    "Result: ${currentBuild.result} ${result_mark}\\n" +
                    "Branch: ${env.BRANCH_NAME}\\n" +
                    "Git Hash: ${githash}\\n" +
                    "Elapsed Time: ${duration} Mins\\n" +
                    "Build Link: https://cd.pingcap.net/blue/organizations/jenkins/build_tiflash_multi_branch/detail/master/${env.BUILD_NUMBER}/pipeline\\n" +
                    "Job Page: https://cd.pingcap.net/blue/organizations/jenkins/build_tiflash_multi_branch/activity/"
            print feishumsg
            node("master") {
                withCredentials([string(credentialsId: 'tiflash-lark-channel-patrol-hook', variable: 'TOKEN')]) {
                    sh """
                      curl -X POST ${TOKEN} -H 'Content-Type: application/json' \
                      -d '{
                        "msg_type": "text",
                        "content": {
                          "text": "$feishumsg"
                        }
                      }'
                    """
                }
            }
        }
    }
}
