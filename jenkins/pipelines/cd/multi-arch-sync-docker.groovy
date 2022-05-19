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
                if (IF_ENTERPRISE == "true") {
                    def builds_enterprise = [:]
                    for (item in release_repo) {
                        def product = "${item}"
                        builds_enterprise["sync ${item} enterprise docker image"] = {
                            libs.retag_docker_image_for_ga(product, true)
                        }

                    }
                    parallel builds_enterprise
                } else {
                    def builds_community = [:]
                    for (item in release_repo) {
                        def product = "${item}"
                        builds_community["sync ${item} community docker image"] = {
                            libs.retag_docker_image_for_ga(product, false)
                        }

                    }
                    builds_community["push tidb-monitor-initializer community docker image"] = {
                        libs.build_push_tidb_monitor_initializer_image()
                    }
                    builds_community["push tidb-monitor-reloader arm64"] = {
                        build job: 'build-arm-image',
                                wait: true,
                                parameters: [
                                        [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"],
                                        [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: "${RELEASE_BRANCH}"]
                                ]
                    }

                    parallel builds_community
                }

            }
        }
    }
}
