if (params.containsKey("release_test")) {
    echo "this build is triggered by qa for release testing"
}

def checkoutTiFlash(commit, pullId) {
    checkout(changelog: false, poll: false, scm: [
            $class: "GitSCM",
            branches: [
                    [name: "${commit}"],
            ],
            userRemoteConfigs: [
                    [
                            url: "git@github.com:pingcap/tiflash-scripts.git",
                            refspec: "+refs/pull/${pullId}/*:refs/remotes/origin/pr/${pullId}/*",
                            credentialsId: "github-sre-bot-ssh",
                    ]
            ],
            extensions: [
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
            ],
    ])
}

def fallback() {

    def label = "test-tiflash-fallback"

    catchError {

        podTemplate(name: label, label: label, instanceCap: 3, idleMinutes: 30, containers: [
            containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true,
                    resourceRequestCpu: '2000m', resourceRequestMemory: '8Gi'),
            containerTemplate(name: 'docker', image: 'hub.pingcap.net/tiflash/docker:build-essential-java',
                    envVars: [ envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375')],
                    alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
            containerTemplate(name: 'docker-ops-ci', image: 'hub.pingcap.net/tiflash/ops-ci:v10',
                    envVars: [ envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375') ],
                    alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
        ]) {
            node(label) {
                def ticsTag = ({
                    def m = params.ghprbCommentBody =~ /tics\s*=\s*([^\s\\]+)(\s|\\|$)/
                    if (m) {
                        return "${m.group(1)}"
                    }
                    return params.ticsTag ?: params.ghprbTargetBranch ?: 'raft'
                }).call()

                echo "ticsTag=${params.ticsTag} ghprbTargetBranch=${params.ghprbTargetBranch} ghprbCommentBody=${params.ghprbCommentBody}"

                stage("Checkout") {
                    container("docker") {
                        sh "chown -R 1000:1000 ./"
                    }
                    dir("tiflash") {
                        checkoutTiFlash("${params.ghprbActualCommit}", "${params.ghprbPullId}")
                    }
                    dir("tispark") {
                        git url: "https://github.com/pingcap/tispark.git", branch: "tiflash-ci-test"
                        container("docker") {
                            sh """
                            archive_url=${FILE_SERVER_URL}/download/builds/pingcap/tiflash/cache/tiflash-m2-cache_latest.tar.gz
                            if [ ! -d /root/.m2 ]; then curl -sL \$archive_url | tar -zx -C /root || true; fi
                            """
                            sh "mvn install -Dmaven.test.skip=true"
                        }
                    }
                }
                dir("tiflash/integrated") {
                    stage("OPS TI Test") {
                        container("docker-ops-ci") {
                            if (fileExists("tests/ci/jenkins.sh")) {
                                try {
                                    sh "tests/ci/jenkins.sh"
                                } catch (err) {
                                    throw err
                                }
                            }
                        }
                    }
                }
                dir("tiflash/tests/maven") {
                    stage("Test") {
                        container("docker") {

                            def firstTrial = true
                            retry(20) {
                                if (firstTrial) {
                                    firstTrial = false
                                } else {
                                    sleep time: 5, unit: "SECONDS"
                                }
                                sh "docker pull hub.pingcap.net/tiflash/tics:$ticsTag"
                            }

                            try {
                                sh "TAG=$ticsTag sh -xe run.sh"
                            } catch(e) {
                                sh "for f in \$(find log -name '*.log'); do echo \"LOG: \$f\"; tail -500 \$f; done"
                                throw e
                            }
                        }
                    }
                }
            }
        }
    }

    stage('Summary') {
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def msg = "Build Result: `${currentBuild.currentResult}`" + "\n" +
                "Elapsed Time: `${duration} mins`" + "\n" +
                "${env.RUN_DISPLAY_URL}"

        echo "${msg}"

    }

}


node("toolkit") {
    container('toolkit') {
        withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
            if (sh(script: "inv resolve-dir --path .ci --repo pingcap/tiflash-scripts --commit ${params.ghprbActualCommit} --auth sre-bot:${TOKEN} && test -f integration_test.groovy", returnStatus: true) == 0) {
                load 'integration_test.groovy'
            } else {
                fallback()
            }
        }
    }
}
