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
* @RELEASE_TAG
*/

def task = "release-check"
def check_image = { comps, edition, registry ->
    podTemplate(name: task, label: task, instanceCap: 5, idleMinutes: 120, containers: [
            containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true),
            containerTemplate(name: 'docker', image: 'hub.pingcap.net/lilinghai/release-checker:master2',alwaysPull: true, envVars: [
                    envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
            ], ttyEnabled: true, command: 'cat'),
    ]) {
        node(task) {
            container("docker") {
                unstash 'qa'
                dir("qa/tools/release-checker/checker") {
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
            dir("qa/tools/release-checker/checker") {
                sh "python3 main.py pingcap --arch ${arch} ${RELEASE_TAG}.json ${RELEASE_TAG} ${edition}"
            }
        }
    } else {
        def imageName = "hub.pingcap.net/lilinghai/release-checker:tiflash"
        def label = task + "-tiflash"
        podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 120, containers: [
                containerTemplate(name: 'main', image: imageName,alwaysPull: true,
                        ttyEnabled: true, command: 'cat'),
        ]) {
            node(label) {
                container("main") {
                    unstash 'qa'
                    dir("qa/tools/release-checker/checker") {
                        sh "python3 main.py pingcap --arch ${arch} ${RELEASE_TAG}.json ${RELEASE_TAG} ${edition}"
                    }
                }
            }
        }
    }
}

def check_tiup = { comps, label ->
    if (label == "mac" || label == "arm") {
        node(label) {
            unstash 'qa'
            dir("qa/tools/release-checker/checker") {
                comps.each {
                    sh "python3 main.py tiup -c $it ${RELEASE_TAG}.json ${RELEASE_TAG}"
                }
            }
        }
    } else {
        def imageName = "hub.pingcap.net/lilinghai/release-checker:tiflash"
        label = task + "-tiflash"
        podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 120, containers: [
                containerTemplate(name: 'main', image: imageName,alwaysPull: true,
                        ttyEnabled: true, command: 'cat'),
        ]) {
            node(label) {
                container("main") {
                    unstash 'qa'
                    dir("qa/tools/release-checker/checker") {
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
  "dumpling_commit": "${DUMPLING_VERSION}"
}
__EOF__
                        """
            stash includes: "${RELEASE_TAG}.json", name: "release.json"
            dir("qa") {
                checkout scm: [$class           : 'GitSCM',
                               branches         : [[name: "release-checker"]],
                               extensions       : [[$class: 'LocalBranch']],
                               userRemoteConfigs: [[credentialsId: 'github-llh-ssh', url: 'git@github.com:pingcap/qa.git']]]

            }
            sh "cp ${RELEASE_TAG}.json qa/tools/release-checker/checker"
            stash includes: "qa/**", name: "qa"
        }
    }
}
parallel(
//        "Image Community Docker [tidb,tikv,pd]": {
//            check_image(["tidb", "tikv", "pd"], "community", "registry.hub.docker.com")
//        },
//        "Image Community Docker [tiflash]": {
//            check_image(["tiflash"], "community", "registry.hub.docker.com")
//        },
//        "Image Community Docker [br,binlog,lightning,cdc,dumpling]": {
//            check_image(["br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling"], "community", "registry.hub.docker.com")
//        },
//        "Image Enterprise Docker [tidb,tikv,pd]": {
//            check_image(["tidb", "tikv", "pd"], "enterprise", "registry.hub.docker.com")
//        },
//        "Image Enterprise Docker [tiflash]": {
//            check_image(["tiflash"], "enterprise", "registry.hub.docker.com")
//        },
//        "Image Enterprise Docker [binlog,lightning,cdc]": {
//            check_image(["tidb-binlog", "tidb-lightning", "ticdc"], "enterprise", "registry.hub.docker.com")
//        },
//        "Image Community Ucloud [tidb,tikv,pd]": {
//            check_image(["tidb", "tikv", "pd"], "community", "uhub.service.ucloud.cn")
//        },
//        "Image Community Ucloud [tiflash]": {
//            check_image(["tiflash"], "community", "uhub.service.ucloud.cn")
//        },
//        "Image Community Ucloud [br,binlog,lightning,cdc,dumpling]": {
//            check_image(["br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling"], "community", "uhub.service.ucloud.cn")
//        },
//        "Tiup Linux Amd64 [tidb,tikv,pd]": {
//            check_tiup(["tidb", "tikv", "pd"], task)
//        },

//        "Tiup Linux Amd64 [tiflash]": {
//            check_tiup(["tiflash"], task)
//        },
//        "Tiup Linux Amd64 [br,binlog,lightning,importer,cdc,dumpling]": {
//            check_tiup(["br", "tidb-binlog", "tidb-lightning", "tikv-importer", "ticdc", "dumpling"], task)
//        },
//        "Tiup Linux Amd64 [tidb,tikv,pd]": {
//            check_tiup(["tidb", "tikv", "pd"], task)
//        },
//        "Tiup Linux Arm64 [tidb,tikv,pd]": {
//            check_tiup(["tidb", "tikv", "pd"], "arm")
//        },
//        "Tiup Linux Arm64 [tiflash]": {
//            check_tiup(["tiflash"], "arm")
//        },
//        "Tiup Linux Arm64 [br,binlog,lightning,cdc,dumpling]": {
//            check_tiup(["br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling"], "arm")
//        },
//        "Tiup Darwin Amd64 [tidb,tikv,pd]": {
//            check_tiup(["tidb", "tikv", "pd"], "mac")
//        },
//        "Tiup Darwin Amd64 [tiflash]": {
//            check_tiup(["tiflash"], "mac")
//        },
//        "Tiup Darwin Amd64 [br,binlog,lightning,cdc,dumpling]": {
//            check_tiup(["br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling"], "mac")
//        },
        "Image Community Docker": {
            check_image(["tidb", "tikv", "pd", "tiflash", "br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling"], "community", "registry.hub.docker.com")
        },
        "Image Enterprise Docker": {
            check_image(["tidb", "tikv", "pd", "tiflash", "br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling"], "enterprise", "registry.hub.docker.com")
        },
        "Image Community Ucloud": {
            check_image(["tidb", "tikv", "pd", "tiflash", "br", "tidb-binlog", "tidb-lightning", "ticdc", "dumpling"], "community", "uhub.service.ucloud.cn")
        },

        "Tiup Linux Amd64": {
            check_tiup(["tidb", "tikv", "pd", "tiflash", "br", "tidb-binlog", "tidb-lightning", "tikv-importer", "ticdc", "dumpling"], task)
        },
        "Tiup Linux Arm64": {
            check_tiup(["tidb", "tikv", "pd", "tiflash", "br", "tidb-binlog", "tidb-lightning", "tikv-importer", "ticdc", "dumpling"], "arm")
        },
        "Tiup Darwin Amd64": {
            check_tiup(["tidb", "tikv", "pd", "tiflash", "br", "tidb-binlog", "tidb-lightning", "tikv-importer", "ticdc", "dumpling"], "mac")
        },

        "Pingcap Community Linux Amd64": {
            check_pingcap("linux-amd64", "community")
        },
        "Pingcap Enterprise Linux Amd64": {
            check_pingcap("linux-amd64", "enterprise")
        },
        "Pingcap Community Linux Arm64": {
            check_pingcap("linux-arm64", "community")
        },
        "Pingcap Enterprise Linux Arm64": {
            check_pingcap("linux-arm64", "enterprise")
        }
)
