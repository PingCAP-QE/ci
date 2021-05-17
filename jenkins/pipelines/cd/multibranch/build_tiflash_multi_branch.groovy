def label = "build-tiflash-release"
def slackcolor = 'good'

// properties([
//         parameters([
//                 string(name: 'BUILD_BRANCH', defaultValue: 'master', description: '', trim: true),
//         ])
// ])

def checkoutTiCS(branch) {
    if (branch.startsWith("refs/tags")) {
        checkout(changelog: false, poll: true, scm: [
                $class                           : "GitSCM",
                branches                         : [
                        [name: "${branch}"],
                ],
                userRemoteConfigs                : [
                        [
                                url          : "git@github.com:pingcap/tics.git",
                                refspec      : "+${branch}:${branch}",
                                credentialsId: "github-sre-bot-ssh",
                        ]
                ],
                extensions                       : [
                        [$class             : 'SubmoduleOption',
                         disableSubmodules  : false,
                         parentCredentials  : true,
                         recursiveSubmodules: true,
                         trackingSubmodules : false,
                         reference          : ''],
                        [$class: 'PruneStaleBranch'],
                        [$class: 'CleanBeforeCheckout'],
                        [$class: 'LocalBranch'],
                        [$class: 'CloneOption', noTags: true, timeout: 60]
                ],
                doGenerateSubmoduleConfigurations: false,
        ])
    } else {
        checkout(changelog: false, poll: true, scm: [
                $class                           : "GitSCM",
                branches                         : [
                        [name: "${branch}"],
                ],
                userRemoteConfigs                : [
                        [
                                url          : "git@github.com:pingcap/tics.git",
                                refspec      : "+refs/heads/*:refs/remotes/origin/*",
                                credentialsId: "github-sre-bot-ssh",
                        ]
                ],
                extensions                       : [
                        [$class             : 'SubmoduleOption',
                         disableSubmodules  : false,
                         parentCredentials  : true,
                         recursiveSubmodules: true,
                         trackingSubmodules : false,
                         reference          : ''],
                        [$class: 'PruneStaleBranch'],
                        [$class: 'CleanBeforeCheckout'],
                        [$class: 'LocalBranch']
                ],
                doGenerateSubmoduleConfigurations: false,
        ])
    }
}


def buildTiflash(mode) {
    def githash = null
    dir("tics") {
        def ws=pwd()

        stage("Checkout") {
            container("builder") {
                sh "rm -rf ./*"
            }
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            container("docker") {
                sh "chown -R 1000:1000 ./"
            }
            // 如果不是 TAG，直接传入 branch_name ； 否则就应该 checkout 到 refs/tags 下
            def target_branch = (env.TAG_NAME == null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
            println target_branch

            dir("/home/jenkins/agent/code-archive") {
                // delete to clean workspace in case of agent pod reused lead to conflict.
                deleteDir()
                // copy code from nfs cache
                container("builder") {
                    if(fileExists("/nfs/cache/git-test/src-tics.tar.gz")){
                        timeout(5) {
                            sh """
                            cp -R /nfs/cache/git-test/src-tics.tar.gz*  ./
                            tar -xzf src-tics.tar.gz -C ${ws} --strip-components=1
                        """
                        }
                    }
                }
                dir("${ws}") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        echo "Not a valid git folder: ${ws}"
                        echo "Clean dir then get tidb src code from fileserver"
                        deleteDir()
                    }
                    checkoutTiCS(target_branch)
                    sh """
                    git branch
                    git symbolic-ref --short HEAD
                    """
                    githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                }
            }
        }

        stage("Build") {
            timeout(120) {
                container("builder") {
                    if (env.TAG_NAME == null && mode != "nightly") {
                        // merge
                        // using this script will not build tiflash proxy which should be much faster
                        sh "NPROC=12 CMAKE_BUILD_TYPE=RELWITHDEBINFO release-centos7/build/build-tiflash-ci.sh"
                    } else {
                        // release
                        sh "NPROC=12 release-centos7/build/build-release.sh"
                        sh "ls release-centos7/build-release/"
                        sh "ls release-centos7/tiflash/"
                    }
                }
            }
        }
        stage("Upload") {
            container("builder") {
                // 用 sha1 标志 branch 上最新的 commit ID，看以此去获取 tarball 的路径
                def refspath = "refs/pingcap/tics/${env.BRANCH_NAME}/sha1"
                def refspath1 = "refs/pingcap/tiflash/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/tiflash/${env.BRANCH_NAME}/${githash}/centos7/tiflash.tar.gz"
                if (mode == "nightly") {
                    filepath = "builds/pingcap/tiflash/release/${env.BRANCH_NAME}/${githash}/centos7/tiflash.tar.gz"
                }
                sh """
                        cd release-centos7/
                        tar -czvf tiflash.tar.gz tiflash
                        echo "${githash}" > sha1

                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        curl -F ${refspath1}=@sha1 ${FILE_SERVER_URL}/upload
                        curl -F ${filepath}=@tiflash.tar.gz ${FILE_SERVER_URL}/upload
                        """
            }
            container("docker") {
                if (env.TAG_NAME == null && mode != "nightly") {
                    // merge
                    sh """
                            cd release-centos7
                            while ! make image_tiflash_ci ;do echo "fail @ `date "+%Y-%m-%d %H:%M:%S"`"; sleep 60; done
                            """
                    docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
                        sh """
                                docker tag hub.pingcap.net/tiflash/tiflash-ci-centos7 hub.pingcap.net/tiflash/tiflash:${env.BRANCH_NAME}
                                docker tag hub.pingcap.net/tiflash/tiflash-ci-centos7 hub.pingcap.net/tiflash/tics:${env.BRANCH_NAME}
                                docker push hub.pingcap.net/tiflash/tiflash:${env.BRANCH_NAME}
                                docker push hub.pingcap.net/tiflash/tics:${env.BRANCH_NAME}
                                """
                    }
                } else {
                    // release
                    sh """
                            cd release-centos7
                            while ! make image_tiflash_release ;do echo "fail @ `date "+%Y-%m-%d %H:%M:%S"`"; sleep 60; done
                            """
                    docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
                        if(mode=="nightly"){
//                            sh """
//                                docker tag hub.pingcap.net/tiflash/tiflash-server-centos7 hub.pingcap.net/release/tiflash/tiflash:${env.BRANCH_NAME}
//                                docker tag hub.pingcap.net/tiflash/tiflash-server-centos7 hub.pingcap.net/release/tiflash/tics:${env.BRANCH_NAME}
//                                docker push hub.pingcap.net/release/tiflash/tiflash:${env.BRANCH_NAME}
//                                docker push hub.pingcap.net/release/tiflash/tics:${env.BRANCH_NAME}
//                                """
                        }else{
                            sh """
                                docker tag hub.pingcap.net/tiflash/tiflash-server-centos7 hub.pingcap.net/tiflash/tiflash:${env.BRANCH_NAME}
                                docker tag hub.pingcap.net/tiflash/tiflash-server-centos7 hub.pingcap.net/tiflash/tics:${env.BRANCH_NAME}
                                docker push hub.pingcap.net/tiflash/tiflash:${env.BRANCH_NAME}
                                docker push hub.pingcap.net/tiflash/tics:${env.BRANCH_NAME}
                                """
                        }
                    }
                }
            }
        }
    }
}


try {
    podTemplate(name: label, label: label,
            nodeSelector: 'role_type=slave',
            instanceCap: 5,workspaceVolume: emptyDirWorkspaceVolume(memory: true),
            containers: [
                    containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true),
                    containerTemplate(name: 'docker', image: 'hub.pingcap.net/zyguan/docker:build-essential',
                            alwaysPullImage: false, envVars: [
                            envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
                    ], ttyEnabled: true, command: 'cat'),
                    containerTemplate(name: 'builder', image: 'hub.pingcap.net/tiflash/tiflash-builder',
                            alwaysPullImage: true, ttyEnabled: true, privileged: true, command: 'cat',
                            resourceRequestCpu: '12000m', resourceRequestMemory: '20Gi',
                            resourceLimitCpu: '16000m', resourceLimitMemory: '48Gi'),
            ]) {
        parallel(
                "nightly build": {
                    if ("master" == "${env.BRANCH_NAME}") {
                        node(label) {
                            buildTiflash("nightly")
                        }
                    }
                },
                "normal build": {
                    node(label) {
                        buildTiflash("normal")
                    }
                }
        )
    }
    currentBuild.result = "SUCCESS"
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

    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}\n @here"
    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#tiflash-dev', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
