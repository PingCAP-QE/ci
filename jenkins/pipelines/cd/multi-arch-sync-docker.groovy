package cd

/*
* @RELEASE_TAG
* @RELEASE_BRANCH
* @IF_ENTERPRISE
* @DEBUG_MODE
*/


def libs
def release_repo
node('delivery') {
    container('delivery') {
        stage('prepare') {
            dir('centos7') {
                checkout scm
                libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
                release_repo = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "tidb-lightning", "tidb-monitor-initializer"]
                if (RELEASE_TAG >= "v5.3.0") {
                    release_repo = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "dm", "tidb-lightning", "tidb-monitor-initializer", "ng-monitoring"]
                }
            }

            stage('multi-arch docker image') {
                if (IF_ENTERPRISE == "true") {
                    def builds_enterprise = [:]
                    for (item in release_repo) {
                        def product = "${item}"
                        builds_enterprise["sync ${item} enterprise docker image"] = {
                            libs.retag_docker_image_for_ga(product, "true", DEBUG_MODE)
                        }

                    }
                    parallel builds_enterprise
                } else {
                    def builds_community = [:]
                    for (item in release_repo) {
                        def product = "${item}"
                        builds_community["sync ${item} community docker image"] = {
                            libs.retag_docker_image_for_ga(product, "false", DEBUG_MODE)
                        }
                    }
                    parallel builds_community
                }

            }
        }
    }
}
