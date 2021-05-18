def slackcolor = 'good'
def githash

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
                        timeout(60) {
                            sh """
                            rm ~/.gitconfig || true
                            rm -rf bin/*
                            rm -rf /home/jenkins/.target/*
                            grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                            if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                                echo using gcc 8
                                source /opt/rh/devtoolset-8/enable
                            fi
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
                        timeout(60) {
                            sh """
                            rm ~/.gitconfig || true
                            rm -rf bin/*
                            grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                            if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                                echo using gcc 8
                                source /opt/rh/devtoolset-8/enable
                            fi
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
                def filepath = "builds/pingcap/tikv/${githash}/centos7/tikv-server.tar.gz"
                container("rust") {
                    timeout(10) {
                        sh """
                        echo "${githash}" > sha1
                        tar czvf tikv-server.tar.gz bin/*
                        curl -F ${filepath}=@tikv-server.tar.gz ${FILE_SERVER_URL}/upload --fail
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
    }

    stage("Push tikv Docker") {
        build job: 'build_image_hash', wait: false, parameters: [[$class: 'StringParameterValue', name: 'REPO', value: "tikv"], [$class: 'StringParameterValue', name: 'COMMIT_ID', value: githash], [$class: 'StringParameterValue', name: 'IMAGE_TAG', value: env.BRANCH_NAME]]
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