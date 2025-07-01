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
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: RELEASE_BRANCH]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 3]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/monitoring.git']]]
                    sh """
                    mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                    GOPATH=${ws}/go go build -o pull-monitoring  cmd/monitoring.go
                    """
                    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                        sh """
                        ./pull-monitoring  --config=monitoring.yaml --tag=${version} --token=$TOKEN
                        ls monitor-snapshot/
                        ls monitor-snapshot/${version}/
                        ls monitor-snapshot/${version}/operator
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
                            withCredentials([usernamePassword(credentialsId: 'harbor-pingcap', usernameVariable: 'harborUser', passwordVariable: 'harborPassword')]) {
                                sh """
                            docker version
                            docker login -u ${harborUser} -p ${harborPassword} hub.pingcap.net

                            wget https://github.com/docker/buildx/releases/download/v0.8.0/buildx-v0.8.0.linux-amd64 -O /usr/bin/buildx
                            chmod +x /usr/bin/buildx
                            docker run --rm --privileged multiarch/qemu-user-static:6.1.0-8 --reset
                            buildx create --name mybuilder --platform=linux/arm64,linux/amd64 --use || true

                            # buildx build --platform=linux/arm64,linux/amd64 --push -t pingcap/tidb-monitor-initializer:${RELEASE_TAG} monitor-snapshot/${version}/operator
                            # buildx build --platform=linux/arm64,linux/amd64 --push -t pingcap/tidb-monitor-reloader:v1.0.1 -f reload/Dockerfile_buildx .

                            buildx build --platform=linux/arm64,linux/amd64 --push -t hub.pingcap.net/qa/tidb-monitor-initializer:${RELEASE_TAG} monitor-snapshot/${version}/operator
                            # buildx build --platform=linux/arm64,linux/amd64 --push -t hub.pingcap.net/qa/tidb-monitor-reloader:v1.0.1 -f reload/Dockerfile_buildx .
                            """
                            }
                        }

                        if (NEED_SYNC_TO_DOCKER == "true") {
                            harbor_tmp_image_name_initializer = "hub.pingcap.net/qa/tidb-monitor-initializer:${RELEASE_TAG}"
                            sync_dest_image_name_initializer = "pingcap/tidb-monitor-initializer:${RELEASE_TAG}"
                            sync_image_params_initializer = [
                                    string(name: 'triggered_by_upstream_ci', value: "docker-common-nova"),
                                    string(name: 'SOURCE_IMAGE', value: harbor_tmp_image_name_initializer),
                                    string(name: 'TARGET_IMAGE', value: harbor_tmp_image_name_initializer),
                            ]
                            build(job: "jenkins-image-syncer", parameters: sync_image_params_initializer, wait: true, propagate: true)

                            // harbor_tmp_image_name_reloader = "hub.pingcap.net/qa/tidb-monitor-reloader:${RELEASE_TAG}"
                            // sync_dest_image_name_reloader = "pingcap/tidb-monitor-reloader:${RELEASE_TAG}"
                            // sync_image_params_reloader = [
                            //         string(name: 'triggered_by_upstream_ci', value: "docker-common-nova"),
                            //         string(name: 'SOURCE_IMAGE', value: harbor_tmp_image_name_reloader),
                            //         string(name: 'TARGET_IMAGE', value: harbor_tmp_image_name_reloader),
                            // ]
                            // build(job: "jenkins-image-syncer", parameters: sync_image_params_reloader, wait: true, propagate: true)
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
