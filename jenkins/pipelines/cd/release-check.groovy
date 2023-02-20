/*
* @TIDB_VERSION
* @TIKV_VERSION
* @PD_VERSION
* @TIFLASH_VERSION
* @BR_VERSION
* @BINLOG_VERSION
* @LIGHTNING_VERSION
* @IMPORTER_VERSION
* @TOOLS_VERSION
* @CDC_VERSION
* @DUMPLING_VERSION
* @DM_VERSION
* @RELEASE_TAG
*/

def task = "release-check"
def check_image = { comps, edition, registry ->
    podTemplate(name: task, label: task, instanceCap: 5, idleMinutes: 120, containers: [
            containerTemplate(name: 'dockerd', image: 'docker:20-dind', privileged: true, command:'dockerd --host=tcp://localhost:2375'),
            containerTemplate(name: 'docker', image: 'hub.pingcap.net/jenkins/release-checker:master', alwaysPullImage: true, envVars: [
                    envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
            ], ttyEnabled: true, command: 'cat'),
    ]) {
        node(task) {
            container("docker") {
                unstash 'qa'
                dir("qa/release-checker/checker") {
                    comps.each {
                        sh """
                        python3 main.py image -c $it --registry ${registry} ${RELEASE_TAG}.json ${RELEASE_TAG} ${edition}
                     """
                    }
                }
            }
        }
    }
}

def check_pingcap = { arch, edition ->
    if (arch == "linux-arm64") {
        node("arm") {
            deleteDir()
            unstash 'qa'
            dir("qa/release-checker/checker") {
                sh "python3 main.py pingcap --arch ${arch} ${RELEASE_TAG}.json ${RELEASE_TAG} ${edition}"
            }
        }
    } else {
        def imageName = "hub.pingcap.net/jenkins/release-checker:tiflash"
        def label = task + "-tiflash"
        podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 120, containers: [
                containerTemplate(name: 'main', image: imageName, alwaysPullImage: true,
                        ttyEnabled: true, command: 'cat'),
        ]) {
            node(label) {
                container("main") {
                    unstash 'qa'
                    dir("qa/release-checker/checker") {
                        sh "python3 main.py pingcap --arch ${arch} ${RELEASE_TAG}.json ${RELEASE_TAG} ${edition}"
                    }
                }
            }
        }
    }
}

def check_tiup = { comps, label ->
    if (label == "mac && validator" || label == "arm") {
        node(label) {
            unstash 'qa'
            dir("qa/release-checker/checker") {
                comps.each {
                    sh "python3 main.py tiup -c $it ${RELEASE_TAG}.json ${RELEASE_TAG}"
                }
            }
        }
    } else if (label == "darwin-arm64") {
        nodeLabel = "mac-arm-tiflash"
        node(nodeLabel) {
            unstash 'qa'
            dir("qa/release-checker/checker") {
                comps.each {
                    sh """
                            /opt/homebrew/bin/python3 main.py tiup -c $it ${RELEASE_TAG}.json ${RELEASE_TAG}
                            """
                }
            }
        }
    } else {
        def imageName = "hub.pingcap.net/jenkins/release-checker:tiflash"
        label = task + "-tiflash"
        podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 120, containers: [
                containerTemplate(name: 'main', image: imageName, alwaysPullImage: true,
                        ttyEnabled: true, command: 'cat'),
        ]) {
            node(label) {
                container("main") {
                    unstash 'qa'
                    dir("qa/release-checker/checker") {
                        comps.each {
                            sh """
                            python3 main.py tiup -c $it ${RELEASE_TAG}.json ${RELEASE_TAG}
                            """
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
  "tikv-importer_commit": "${IMPORTER_VERSION}",
  "tidb-tools_commit": "${TOOLS_VERSION}",
  "ticdc_commit": "${CDC_VERSION}",
  "dumpling_commit": "${DUMPLING_VERSION}",
  "dm_commit": "${DM_VERSION}"
}
__EOF__
                        """
            stash includes: "${RELEASE_TAG}.json", name: "release.json"
            dir("qa") {
                checkout scm: [$class           : 'GitSCM',
                               branches         : [[name: "main"]],
                               extensions       : [[$class: 'LocalBranch']],
                               userRemoteConfigs: [[credentialsId: 'github-llh-ssh', url: 'https://github.com/PingCAP-QE/ci.git']]]
            }
            sh "cp ${RELEASE_TAG}.json qa/release-checker/checker"
            stash includes: "qa/**", name: "qa"
        }
    }
}
parallel(
//      community  image 校验
        "Image Community Docker": {
            check_image(["tidb", "tikv", "pd", "tiflash", "br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling", "dm"], "community", "registry.hub.docker.com")
        },
        "Image Community Ucloud": {
            check_image(["tidb", "tikv", "pd", "tiflash", "br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling", "dm"], "community", "uhub.service.ucloud.cn")
        },

//        TiUP 在线包校验
        "Tiup Linux Amd64": {
            check_tiup(["tidb", "tikv", "pd", "tiflash", "br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling", "dm"], task)
        },
        "Tiup Linux Arm64": {
            check_tiup(["tidb", "tikv", "pd", "tiflash", "br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling", "dm"], "arm")
        },
        "Tiup Darwin Amd64": {
            check_tiup(["tidb", "tikv", "pd", "tiflash", "br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling"], "mac && validator")
        },
//        tiflash version is dirty,exclude
        "Tiup Darwin Arm64": {
            check_tiup(["tidb", "tikv", "pd", "br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling"], "darwin-arm64")
        },

//        "Image Enterprise Docker": {
//            check_image(["tidb", "tikv", "pd", "tiflash", "br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling","dm"], "enterprise", "registry.hub.docker.com")
//        },

//        TiUp 离线包校验
//        "Pingcap Community Linux Amd64": {
//            check_pingcap("linux-amd64", "community")
//        },
//        "Pingcap Enterprise Linux Amd64": {
//            check_pingcap("linux-amd64", "enterprise")
//        },
//        "Pingcap Community Linux Arm64": {
//            check_pingcap("linux-arm64", "community")
//        },
//        "Pingcap Enterprise Linux Arm64": {
//            check_pingcap("linux-arm64", "enterprise")
//        }
)
