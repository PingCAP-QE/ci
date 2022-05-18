package cd

/*
* @RELEASE_TAG
* @RELEASE_BRANCH
* @IF_ENTERPRISE
*/


def libs
def release_repo
node('delivery') {
    container('delivery') {
        stage('prepare') {
            dir('centos7') {
                checkout scm
                libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
                release_repo = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "dm", "tidb-lightning"]
                if (RELEASE_TAG >= "v5.3.0") {
                    release_repo = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "dm", "tidb-lightning", "ng-monitoring"]
                }
            }

            stage('multi-arch docker image') {
                if (IF_ENTERPRISE==true) {
                    def builds_enterprise = [:]
                    for (item1 in release_repo) {
                        builds_enterprise["sync ${item1} enterprise docker image"] = {
                            libs.retag_docker_image_for_ga(item1, true)
                        }

                    }
                    parallel builds_enterprise
                } else {
                    def builds_community = [:]
                    for (item2 in release_repo) {
                        builds_community["sync ${item2} community docker image"] = {
                            libs.retag_docker_image_for_ga(item2, false)
                        }

                    }
                    builds_community["push tidb-monitor-initializer community docker image"] = {
                        libs.build_push_tidb_monitor_initializer_image()
                    }
                    parallel builds_community
                }

            }
        }
    }
}
