
properties([
        parameters([
                string(defaultValue: '',name: 'RELEASE_TAG',trim: true),
                string(defaultValue: '',name: 'TIDB_VERSION',trim: true),
                string(defaultValue: '',name: 'TIKV_VERSION',trim: true),
                string(defaultValue: '',name: 'PD_VERSION',trim: true),
                string(defaultValue: '',name: 'TIFLASH_VERSION',trim: true),
                string(defaultValue: '',name: 'BR_VERSION',trim: true),
                string(defaultValue: '',name: 'BINLOG_VERSION',trim: true),
                string(defaultValue: '',name: 'LIGHTNING_VERSION',trim: true),
                string(defaultValue: '',name: 'TOOLS_VERSION',trim: true),
                string(defaultValue: '',name: 'CDC_VERSION',trim: true),
                string(defaultValue: '',name: 'DUMPLING_VERSION',trim: true),
                string(defaultValue: '',name: 'DM_VERSION',trim: true),
        ])
])

// TODO
// 1. add tiup check
// 2. add arm64 image check

def task = "pre-release-check"
def check_image = { comps, edition, registry, project ->
    podTemplate(name: task, label: task, instanceCap: 5, idleMinutes: 120, nodeSelector: "kubernetes.io/arch=amd64", containers: [
            containerTemplate(name: 'dockerd', image: 'hub.pingcap.net/jenkins/docker:20.10.14-dind', privileged: true, command:'dockerd --host=tcp://localhost:2375'),
            containerTemplate(name: 'docker', image: 'hub.pingcap.net/jenkins/release-checker:master', alwaysPullImage: true, envVars: [
                    envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
            ], ttyEnabled: true, command: 'cat'),
    ]) {
        node(task) {
            container("docker") {
                unstash 'release-check'
                sh "pwd && ls -alh"
                    stage("traditional image"){
                    dir("release-check") {
                        comps.each {
                            sh script: "python3 main.py image -c $it --registry ${registry} --project qa ${RELEASE_TAG}.json ${RELEASE_TAG} ${edition} --isrcbuild=true", label: "$it"
                        }
                    }
                }
            }
        }
    }
}

stage("prepare") {
    node('delivery') {
        container('delivery') {
            sh """
               cat > ${RELEASE_TAG}.json << __EOF__
{
  "tidb_commit": "${TIDB_VERSION}",
  "tikv_commit": "${TIKV_VERSION}",
  "pd_commit": "${PD_VERSION}",
  "tiflash_commit": "${TIFLASH_VERSION}",
  "br_commit": "${BR_VERSION}",
  "tidb-binlog_commit": "${BINLOG_VERSION}",
  "tidb-lightning_commit": "${LIGHTNING_VERSION}",
  "tidb-tools_commit": "${TOOLS_VERSION}",
  "ticdc_commit": "${CDC_VERSION}",
  "dumpling_commit": "${DUMPLING_VERSION}",
  "dm_commit": "${DM_VERSION}"
}
__EOF__
            """
            stash includes: "${RELEASE_TAG}.json", name: "release.json"
            archiveArtifacts artifacts: "${RELEASE_TAG}.json"
            sh """
            wget ${FILE_SERVER_URL}/download/cicd/scripts/release-check.tar.gz
            tar -xzf release-check.tar.gz
            cp ${RELEASE_TAG}.json release-check/
            """
            stash includes: "release-check/", name: "release-check"
        }
    }
}

stage("Check") {
    def images = ["tidb", "tikv", "tiflash", "pd", "br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling"]
    if (RELEASE_TAG >= "v5.2"){
        images << "dm"
    }
    parallel(
            "X86 Image Community Docker": {
                check_image(images, "community", "hub.pingcap.net", "qa")
            },

            "X86 Image Enterprise Docker": {
                check_image(images, "enterprise", "hub.pingcap.net", "qa")
            },
    )
}
