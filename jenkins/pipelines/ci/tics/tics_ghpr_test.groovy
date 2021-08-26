if (params.containsKey("release_test")) {
    echo "this build is triggered by qa for release testing"
}

def checkoutTiCS(commit, pullId) {
    def refspec = "+refs/heads/*:refs/remotes/origin/*"
    if (pullId) {
        refspec += " +refs/pull/${pullId}/*:refs/remotes/origin/pr/${pullId}/*"
    }
    checkout(changelog: false, poll: false, scm: [
            $class           : "GitSCM",
            branches         : [
                    [name: "${commit}"],
            ],
            userRemoteConfigs: [
                    [
                            url          : "git@github.com:pingcap/tics.git",
                            refspec      : refspec,
                            credentialsId: "github-sre-bot-ssh",
                    ]
            ],
            extensions       : [
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
            ],
    ])
}

def checkoutTiFlash(branch) {
    checkout(changelog: false, poll: true, scm: [
            $class           : "GitSCM",
            branches         : [
                    [name: "${branch}"],
            ],
            userRemoteConfigs: [
                    [
                            url          : "git@github.com:pingcap/tiflash.git",
                            refspec      : "+refs/heads/*:refs/remotes/origin/*",
                            credentialsId: "github-sre-bot-ssh",
                    ]
            ],
            extensions       : [
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
            ],
    ])
}

def run(label, Closure body) {
    podTemplate(name: label, label: label, instanceCap: 5, containers: [
            containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true,
                    resourceRequestCpu: '5000m', resourceRequestMemory: '10Gi',
                    resourceLimitCpu: '16000m', resourceLimitMemory: '32Gi'),
            containerTemplate(name: 'docker', image: 'hub.pingcap.net/jenkins/docker:build-essential-java',
                    alwaysPullImage: true, envVars: [
                    envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
            ], ttyEnabled: true, command: 'cat'),
    ]) {
        node(label) {
            println("${NODE_NAME}")
            body()
        }
    }
}

def fallback() {
    catchError {

        def label = "test-tics"

        def tiflashTag = ({
            def m = params.ghprbCommentBody =~ /tiflash\s*=\s*([^\s\\]+)(\s|\\|$)/
            if (m) {
                return "${m.group(1)}"
            }
            return params.ghprbTargetBranch
        }).call()

        def tidbBranch = ({
            def m = params.ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
            if (m) {
                return "${m.group(1)}"
            }
            return params.ghprbTargetBranch
        }).call()

        println "[debug] tiflashTag: $tiflashTag"
        println "[debug] tidbBranch: $tidbBranch"

        run(label) {
            dir("tics") {
                stage("Checkout") {
                    container("docker") {
                        sh """
                            archive_url=${FILE_SERVER_URL}/download/builds/pingcap/tics/cache/tics-repo_latest.tar.gz
                            if [ ! -d contrib ]; then curl -sL \$archive_url | tar -zx --strip-components=1 || true; fi
                            echo http://dl-cdn.alpinelinux.org/alpine/edge/testing >> /etc/apk/repositories
                            apk add --update --no-cache lcov
                        """
                        sh "chown -R 1000:1000 ./"
                        sh """
                            # if ! grep -q hub.pingcap.net /etc/hosts ; then echo '172.16.10.5 hub.pingcap.net' >> /etc/hosts; fi
                            if [ -d '../tiflash/tests/maven' ]; then
                                cd '../tiflash/tests/maven'
                                docker-compose down || true
                                cd -
                            fi
                        """
                    }
                    checkoutTiCS("${params.ghprbActualCommit}", "${params.ghprbPullId}")
                }
                stage("Test") {
                    timeout(time: 120, unit: 'MINUTES') {
                        container("docker") {
                            sh """
                            while ! docker pull hub.pingcap.net/tiflash/tics:${params.ghprbActualCommit}; do sleep 60; done
                            """
                            dir("tests/docker") {
                                try {
                                    sh "TAG=${params.ghprbActualCommit} BRANCH=${tidbBranch} bash -xe run.sh"
                                } catch (e) {
                                    archiveArtifacts(artifacts: "log/**/*.log", allowEmptyArchive: true)
                                    sh "find log -name '*.log' | xargs tail -n 500"
                                    sh "docker ps -a"
                                    throw e
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
        def slackmsg = "[#${params.ghprbPullId}: ${params.ghprbPullTitle}]" + "\n" +
                "${params.ghprbPullLink}" + "\n" +
                "${params.ghprbPullDescription}" + "\n" +
                "Build Result: `${currentBuild.currentResult}`" + "\n" +
                "Elapsed Time: `${duration} mins` " + "\n" +
                "${env.RUN_DISPLAY_URL}"

        if (currentBuild.currentResult != "SUCCESS") {
            slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
        node("test_go1130_memvolume") {
            echo "Set status for commit(${params.ghprbActualCommit}) according to build result(${currentBuild.currentResult})"
            currentBuild.result = currentBuild.currentResult
            try {
                step([
                        $class            : "GitHubCommitStatusSetter",
                        reposSource       : [$class: "ManuallyEnteredRepositorySource", url: "https://github.com/pingcap/tics"],
                        contextSource     : [$class: "ManuallyEnteredCommitContextSource", context: "idc-jenkins-ci-tics/test"],
                        commitShaSource   : [$class: "ManuallyEnteredShaSource", sha: params.ghprbActualCommit],
                        statusResultSource: [
                                $class : 'ConditionalStatusResultSource',
                                results: [
                                        [$class: 'BetterThanOrEqualBuildResult', result: 'SUCCESS', state: 'SUCCESS', message: "Jenkins job succeeded."],
                                        [$class: 'BetterThanOrEqualBuildResult', result: 'FAILURE', state: 'FAILURE', message: "Jenkins job failed."],
                                        [$class: 'AnyBuildResult', state: 'ERROR', message: 'Jenkins job meets something wrong.'],
                                ]
                        ],
                ]);
            } catch (e) {
                echo "Failed to set commit status: $e"
            }
        }
    }
}


def loader = "toolkit-test-tics"

podTemplate(name: loader, label: loader, instanceCap: 10, containers: [
        containerTemplate(name: 'toolkit', image: 'hub.pingcap.net/qa/ci-toolkit', ttyEnabled: true, command: 'cat'),
]) {
    node(loader) {
        container('toolkit') {
            withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                if (sh(script: "inv resolve-dir --path .ci --repo pingcap/tics --commit ${params.ghprbActualCommit} --auth sre-bot:${TOKEN} && test -f integration_test.groovy", returnStatus: true) == 0) {
                    load 'integration_test.groovy'
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
