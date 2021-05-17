def BUILD_URL = 'git@github.com:tikv/importer.git'
def slackcolor = 'good'
def githash

try {
    node("build_tikv") {
        def ws = pwd()
        deleteDir()
        
        container("rust") {
            stage("Checkout") {
                dir("/home/jenkins/agent/code-archive") {
                // delete to clean workspace in case of agent pod reused lead to conflict.
                deleteDir()
                // copy code from nfs cache
                container("rust") {
                    if(fileExists("/nfs/cache/git/src-importer.tar.gz")){
                        timeout(5) {
                            sh """
                            cp -R /nfs/cache/git/src-importer.tar.gz*  ./
                            mkdir -p ${ws}/go/src/github.com/tikv/importer
                            tar -xzf src-importer.tar.gz -C ${ws}/go/src/github.com/tikv/importer --strip-components=1
                        """
                        }
                    }
                }
                dir("${ws}/go/src/github.com/tikv/importer") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        echo "Not a valid git folder: ${ws}/go/src/github.com/tikv/importer"
                        echo "Clean dir then get tidb src code from fileserver"
                        deleteDir()
                    }
                    // 如果不是 TAG，直接传 branch 给下面的 checkout 语句； 否则就应该 checkout 到 refs/tags 下 .
                    // 值得注意的是，即使传入的是 TAG，环境变量里的 BRANCH_NAME 和 TAG_NAME 同时会是 TAG 名，如 v3.0.0
                    def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
                    println branch

                    // checkout scm: [$class: 'GitSCM', 
                    // branches: [[name: branch]],  
                    // extensions: [[$class: 'LocalBranch']],
                    // userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}"]]]
                    
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
                }
                }
            }

            stage("Build") {
                dir("go/src/github.com/tikv/importer") {
                    timeout(20) {
                        sh """
                        make release && mkdir -p bin/ && mv target/release/tikv-importer bin/
                        """
                    }
                }
            }
        
            stage("Upload") {
                dir("go/src/github.com/tikv/importer") {
                    def refspath = "refs/pingcap/importer/${env.BRANCH_NAME}/sha1"
                    def filepath = "builds/pingcap/importer/${githash}/centos7/importer.tar.gz"
                    timeout(10) {
                        sh """
                        echo "${githash}" > sha1
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        tar czvf importer.tar.gz bin/*
                        curl -F ${filepath}=@importer.tar.gz ${FILE_SERVER_URL}/upload
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
    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}\n @here"
    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}