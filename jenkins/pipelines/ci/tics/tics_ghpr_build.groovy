// properties([
//         parameters([
//                 string(name: 'ghprbActualCommit', defaultValue: '', description: '', trim: true),
//                 string(name: 'ghprbPullId', defaultValue: '', description: '', trim: true),
//                 string(name: 'ghprbPullTitle', defaultValue: '', description: '', trim: true),
//                 string(name: 'ghprbPullLink', defaultValue: '', description: '', trim: true),
//                 string(name: 'ghprbPullDescription', defaultValue: '', description: '', trim: true),
//                 string(name: 'ghprbCommentBody', defaultValue: '', description: '', trim: true),
//                 string(name: 'ghprbTargetBranch', defaultValue: 'master', description: '', trim: true),
//                 string(name: 'tiflashTag', defaultValue: 'master', description: '', trim: true),
//         ])
// ])

def checkoutTiCS(commit, pullId) {
    def refspec = "+refs/heads/*:refs/remotes/origin/*"
    if (pullId) {
        refspec += " +refs/pull/${pullId}/*:refs/remotes/origin/pr/${pullId}/*"
    }
    checkout(changelog: false, poll: false, scm: [
            $class                           : "GitSCM",
            branches                         : [
                    [name: "${commit}"],
            ],
            userRemoteConfigs                : [
                    [
                            url          : "git@github.com:pingcap/tics.git",
                            refspec      : refspec,
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
            ],
            doGenerateSubmoduleConfigurations: false,
    ])
}

def fallback() {
    catchError {

        def label = "build-tics"

        def tiflashTag = ({
            def m = ghprbCommentBody =~ /tiflash\s*=\s*([^\s\\]+)(\s|\\|$)/
            if (m) {
                return "${m.group(1)}"
            }
            return params.tiflashTag ?: ghprbTargetBranch ?: 'master'
        }).call()

        podTemplate(name: label, label: label, instanceCap: 5,
                workspaceVolume: emptyDirWorkspaceVolume(memory: true),
                nodeSelector: 'role_type=slave',
                containers: [
                        containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true),
                        containerTemplate(name: 'docker', image: 'hub.pingcap.net/zyguan/docker:build-essential',
                                alwaysPullImage: true, envVars: [
                                envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
                        ], ttyEnabled: true, command: 'cat'),
                        containerTemplate(name: 'builder', image: 'hub.pingcap.net/tiflash/tiflash-builder-ci',
                                alwaysPullImage: true, ttyEnabled: true, command: 'cat',
                                resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                                resourceLimitCpu: '10000m', resourceLimitMemory: '30Gi'),
                ]) {

            node(label) {

                dir("tics") {
                    stage("Checkout") {
                        container("docker") {
                            sh """
                            archive_url=${FILE_SERVER_URL}/download/builds/pingcap/tics/cache/tics-repo_latest.tar.gz
                            if [ ! -d contrib ]; then curl -sL \$archive_url | tar -zx --strip-components=1 || true; fi
                            """
                            sh "chown -R 1000:1000 ./"
                            // sh "if ! grep -q hub.pingcap.net /etc/hosts ; then echo '172.16.10.5 hub.pingcap.net' >> /etc/hosts; fi"
                        }
                        checkoutTiCS("${ghprbActualCommit}", "${ghprbPullId}")
                    }
                    stage("Build") {
                        timeout(time: 70, unit: 'MINUTES') {
                            container("builder") {
                                sh "NPROC=5 BUILD_BRANCH=${ghprbTargetBranch} release-centos7/build/build-tiflash-ci.sh"
                            }
                            container("docker") {
                                sh """
                                cd release-centos7
                                while ! make image_tiflash_ci ;do echo "fail @ `date "+%Y-%m-%d %H:%M:%S"`"; sleep 60; done
                                """
                            }
                        }
                    }
                    stage("Upload") {
                        container("docker") {
                            docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
                                sh """
                                docker tag hub.pingcap.net/tiflash/tiflash-ci-centos7 hub.pingcap.net/tiflash/tics:${ghprbActualCommit}
                                docker push hub.pingcap.net/tiflash/tics:${ghprbActualCommit}

			                    docker tag hub.pingcap.net/tiflash/tiflash-ci-centos7 hub.pingcap.net/tiflash/tiflash:${ghprbActualCommit}
                                docker push hub.pingcap.net/tiflash/tiflash:${ghprbActualCommit}
				                """
                                if (ghprbPullId) {
                                    sh """
                                    docker tag hub.pingcap.net/tiflash/tiflash-ci-centos7 hub.pingcap.net/tiflash/tics:pr-${ghprbPullId}
                                    docker push hub.pingcap.net/tiflash/tics:pr-${ghprbPullId}
                                    """
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    stage('Summary') {
        echo "Send slack here ..."
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
                "${ghprbPullLink}" + "\n" +
                "${ghprbPullDescription}" + "\n" +
                "Build Result: `${currentBuild.currentResult}`" + "\n" +
                "Elapsed Time: `${duration} mins` " + "\n" +
                "${env.RUN_DISPLAY_URL}"

        if (currentBuild.currentResult != "SUCCESS") {
            slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
    }
}


def loader = "toolkit-build-tics"

podTemplate(name: loader, label: loader, instanceCap: 3, containers: [
        containerTemplate(name: 'toolkit', image: 'hub.pingcap.net/qa/ci-toolkit', ttyEnabled: true, command: 'cat'),
]) {
    node(loader) {
        container('toolkit') {
            withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                if (sh(script: "inv resolve-dir --path .ci --repo pingcap/tics --commit ${params.ghprbActualCommit} --auth sre-bot:${TOKEN} && test -f build.groovy", returnStatus: true) == 0) {
                    load 'build.groovy'
                } else {
                    fallback()
                }
            }
        }
    }
}
stage("upload status") {
    node {
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
    }
}
