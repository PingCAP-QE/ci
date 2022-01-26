def label = "build-tiflash-release"
def slackcolor = 'good'
githash = null

stage("Get Hash") {
    node("${GO_TEST_SLAVE}") {
        def target_branch = (env.TAG_NAME == null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
        echo "Target Branch: ${target_branch}"
        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
        githash = sh(returnStdout: true, script: "python gethash.py -repo=tics -source=github -version=${target_branch} -s=${FILE_SERVER_URL}").trim()
    }
}

def release_amd64(repo,hash,mode) {
    def filepath = "builds/pingcap/tiflash/${env.BRANCH_NAME}/${hash}/centos7/tiflash.tar.gz"

    if (mode == "nightly") {
        filepath = "builds/pingcap/tiflash/release/${env.BRANCH_NAME}/${hash}/centos7/tiflash.tar.gz"
    }

    echo "release filepath: ${FILE_SERVER_URL}/download/${filepath}"

    def update_cache = false

    if (env.TAG_NAME == null && mode != "nightly") {
        update_cache = true
    }

    def paramsBuild = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: filepath),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: repo),
        string(name: "GIT_HASH", value: hash),
        string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
        [$class: 'BooleanParameterValue', name: 'UPDATE_TIFLASH_CACHE', value: update_cache],
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

    def paramsBuild = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: filepath),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: repo),
        string(name: "RELEASE_TAG", value: release_tag),
        string(name: "DOCKERFILE", value: "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/tiflash"),
        string(name: "RELEASE_DOCKER_IMAGES", value: "hub.pingcap.net/tiflash/tiflash:${env.BRANCH_NAME},hub.pingcap.net/tiflash/tics:${env.BRANCH_NAME}")
    ]

    build job: "docker-common",
            wait: true,
            parameters: paramsBuild
}

try {
    parallel(
        "nightly build": {
            if ("master" == "${env.BRANCH_NAME}") {
                release_amd64("tics", "${githash}", "nightly")
            }
        },
        "normal build": {
            release_amd64("tics", "${githash}", "normal")
        },
    )

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
                withCredentials([string(credentialsId: 'tiflash-regression-lark-channel-hook', variable: 'TOKEN')]) {
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
