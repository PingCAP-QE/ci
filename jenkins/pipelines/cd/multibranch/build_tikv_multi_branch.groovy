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
    stage('Prepare') {
        node("build") {
            def ws = pwd()
            //deleteDir()


            dir("/home/jenkins/agent/code-archive") {
                // delete to clean workspace in case of agent pod reused lead to conflict.
                deleteDir()
                // copy code from nfs cache
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
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        echo "Not a valid git folder: ${ws}/tikv"
                        echo "Clean dir then get tikv src code from fileserver"
                        deleteDir()
                    }
                    // 如果不是 TAG，直接传 branch 给下面的 checkout 语句； 否则就应该 checkout 到 refs/tags 下 .
                    // 值得注意的是，即使传入的是 TAG，环境变量里的 BRANCH_NAME 和 TAG_NAME 同时会是 TAG 名，如 v3.0.0
                    def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
                    println branch

                    // checkout scm: [$class: 'GitSCM',
                    // branches: [[name: branch]],
                    // extensions: [[$class: 'LocalBranch']],
                    // userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:tikv/tikv.git']]]

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
            stash includes: "tikv/**", useDefaultExcludes: false, name: "tikv"
        }
    }

    stage('Build') {
        def builds = [:]

        builds["linux-amd64-centos7"] = {
            node('build') {
                def ws = pwd()
                deleteDir()
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                unstash 'tikv'
                dir("tikv"){
                    // 如果不是 TAG，直接传 branch 给下面的 checkout 语句； 否则就应该 checkout 到 refs/tags 下 .
                    // 值得注意的是，即使传入的是 TAG，环境变量里的 BRANCH_NAME 和 TAG_NAME 同时会是 TAG 名，如 v3.0.0

                }
                dir("tikv") {
                    container("rust") {
                        timeout(90) {
                            sh """
                            rm ~/.gitconfig || true
                            rm -rf bin/*
                            rm -rf /home/jenkins/.target/*
                            echo using gcc 8
                            source /opt/rh/devtoolset-8/enable
                            CARGO_TARGET_DIR=/home/jenkins/.target ROCKSDB_SYS_STATIC=1 make dist_release
                            ./bin/tikv-server --version
                            """
                        }
                    }
                }
                stash includes: "tikv/bin/**", name: "tikv-binary"
            }
        }

        builds["linux-amd64-centos7_failpoint"] = {
            node('build') {
                def ws = pwd()
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                deleteDir()
                unstash 'tikv'

                dir("tikv") {
                    container("rust") {
                        timeout(90) {
                            sh """
                            rm ~/.gitconfig || true
                            rm -rf bin/*
                            echo using gcc 8
                            source /opt/rh/devtoolset-8/enable
                            CARGO_TARGET_DIR=/home/jenkins/.target ROCKSDB_SYS_STATIC=1 make fail_release
                            mv bin/tikv-server bin/tikv-server-failpoint
                            """
                        }
                    }
                }
                stash includes: "tikv/bin/tikv-server-failpoint", name: "tikv-binary-failpoint"
            }
        }

        parallel builds
    }

    stage("Upload") {
        node('build') {
            def ws = pwd()
            deleteDir()
            unstash 'tikv-binary'
            unstash 'tikv-binary-failpoint'
            sh"""
            ls -lR
            """
            dir("tikv") {
                // def refspath = "refs/zhaoyixuan/tikv/${env.BRANCH_NAME}/sha1"
                // def filepath = "builds/zhaoyixuan/tikv/${githash}/centos7/tikv-server.tar.gz"
                def refspath = "refs/pingcap/tikv/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/tikv/${env.BRANCH_NAME}/${githash}/centos7/tikv-server.tar.gz"
                def filepath2 = "builds/pingcap/tikv/${githash}/centos7/tikv-server.tar.gz"
                container("rust") {
                    timeout(10) {
                        sh """
                        echo "${githash}" > sha1
                        tar czvf tikv-server.tar.gz bin/*
                        curl -F ${filepath}=@tikv-server.tar.gz ${FILE_SERVER_URL}/upload --fail
                        curl -F ${filepath2}=@tikv-server.tar.gz ${FILE_SERVER_URL}/upload --fail
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        """
                    }
                    release_one("tikv","${githash}")
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
