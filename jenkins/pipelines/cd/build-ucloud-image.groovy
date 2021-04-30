/**
 * @TIDB_TAG
 * @TIKV_TAG
 * @PD_TAG
 * @BINLOG_TAG
 * @LIGHTNING_TAG
 * @BR_TAG
 * @CDC_TAG
 * @TIFLASH_TAG
 */
def task = "build-image-ucloud"
def push_ucloud_image = { tag, repo ->
    stage("build ${repo}")
// image pull rate limit
    docker.withRegistry("", "llh-docker") {
        sh "docker pull pingcap/${repo}:${tag}"
    }
    docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
        sh """
              docker tag pingcap/${repo}:${tag} uhub.service.ucloud.cn/pingcap/${repo}:${tag}
              docker push uhub.service.ucloud.cn/pingcap/${repo}:${tag}
           """
    }
}
podTemplate(name: task, label: task, instanceCap: 5, idleMinutes: 120, containers: [
        containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true),
        containerTemplate(name: 'docker', image: 'docker:stable', envVars: [
                envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
        ], ttyEnabled: true, command: 'cat'),
]) {
    try {
        node(task) {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            dir("ucloud_image") {
                container('docker') {
                    stage("Build & Push") {
                        push_ucloud_image(TIDB_TAG, "tidb")
                        push_ucloud_image(TIKV_TAG, "tikv")
                        push_ucloud_image(PD_TAG, "pd")
                        push_ucloud_image(BINLOG_TAG, "tidb-binlog")
                        push_ucloud_image(LIGHTNING_TAG, "tidb-lightning")
                        push_ucloud_image(BR_TAG, "br")
                        push_ucloud_image(CDC_TAG, "ticdc")
                        push_ucloud_image(TIFLASH_TAG,"tiflash")
                        push_ucloud_image(DUMPLING_TAG,"dumpling")
                        push_ucloud_image(TIDB_TAG, "tidb-monitor-initializer")
                        push_ucloud_image("v1.0.1", "tidb-monitor-reloader")
                    }
                }
            }
        }
    } catch (Exception e) {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
}

stage('Summary') {
    echo "Send slack here ..."
    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}"
    if (currentBuild.result == "FAILURE") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
