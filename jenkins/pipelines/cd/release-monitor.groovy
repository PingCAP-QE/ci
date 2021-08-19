
def buildSlave = "${GO_BUILD_SLAVE}"

env.DOCKER_HOST = "tcp://localhost:2375"

def version = RELEASE_TAG
if (version == "nightly") {
    version = "master"
}

catchError {
    node(buildSlave) {
        def ws = pwd()
        container("golang") {
            stage('Build') {
                dir("go/src/github.com/pingcap/monitoring") {
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: RELEASE_TAG]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/monitoring.git']]]
                    sh """
                    mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                    GOPATH=${ws}/go go build -o pull-monitoring  cmd/monitoring.go
                    """
                    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                        sh"""
                        ./pull-monitoring  --config=monitoring.yaml --auto-push --tag=${version} --token=$TOKEN
                        """
                    }
                }
                stash includes: "go/src/github.com/pingcap/monitoring/**", name: "monitoring"
            }
        }
    }

    if (RELEASE_TAG != "") {
        stage("Publish Monitor Docker Image") {
            node("delivery") {
                container("delivery") {
                    deleteDir()
                    unstash 'monitoring'
                    dir("go/src/github.com/pingcap/monitoring") {
                        withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                            docker.build("pingcap/tidb-monitor-initializer:${RELEASE_TAG}", "monitor-snapshot/${version}/operator").push()
                        }
                    }
                }
            }
        }
    }


    currentBuild.result = "SUCCESS"
}

stage('Summary') {
    echo "Send slack here ..."
    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}\n @here"
    if (currentBuild.result != "SUCCESS") {
        echo "${slackmsg}"
        //slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
