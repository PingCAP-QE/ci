// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'tikv/tikv'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        label 'mac'
    }
    options {
        timeout(time: 120, unit: 'MINUTES')
        skipDefaultCheckout()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    uname -a
                    echo "-------------------------"
                    echo "REFS: ${REFS}"
                    echo "Repo: ${REFS.repo}"
                    echo "Base ref: ${REFS.base_ref}"
                """
                script {
                    currentBuild.description = "PR #${REFS.pulls[0].number}: ${REFS.pulls[0].title}"
                }
            }
        }

        stage('Checkout') {
            steps {
                dir("tikv") {
                    script {
                        def gitExists = sh(
                            returnStatus: true,
                            script: '[ -d .git ] && git rev-parse --git-dir > /dev/null 2>&1'
                        )

                        if (gitExists != 0) {
                            echo "No valid git repo found, clean checkout"
                            deleteDir()
                        }

                        retry(2) {
                            checkout([
                                $class: 'GitSCM',
                                branches: [[name: "${REFS.pulls[0].sha}"]],
                                doGenerateSubmoduleConfigurations: false,
                                extensions: [
                                    [$class: 'PruneStaleBranch'],
                                    [$class: 'CleanBeforeCheckout']
                                ],
                                userRemoteConfigs: [[
                                    credentialsId: GIT_CREDENTIALS_ID,
                                    refspec: "+refs/pull/${REFS.pulls[0].number}/*:refs/remotes/origin/pr/${REFS.pulls[0].number}/*",
                                    url: "git@github.com:${GIT_FULL_REPO_NAME}.git"
                                ]]
                            ])
                        }

                        sh """
                            git checkout -f ${REFS.pulls[0].sha}
                            git branch
                            git log -1 --oneline
                        """
                    }
                }
            }
        }

        stage('Run Clippy') {
            steps {
                dir("tikv") {
                    timeout(time: 120, unit: 'MINUTES') {
                        sh label: 'make clippy', script: """
                            which rustc
                            rustc --version
                            cargo --version

                            make clippy
                        """
                    }
                }
            }
        }
    }

    post {
        always {
            echo "Build finished with result: ${currentBuild.result}"
        }
        cleanup {
            cleanWs(
                deleteDirs: true,
                disableDeferredWipeout: true
            )
        }
    }
}
