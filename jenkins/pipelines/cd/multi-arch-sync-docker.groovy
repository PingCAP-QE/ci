package cd

/*
* @RELEASE_TAG
* @IF_ENTERPRISE
*/


def libs
node('delivery') {
    container('delivery') {
        stage('prepare') {
            dir('centos7') {
                checkout scm
                libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
            }
            stage('multi-arch docker image') {
                if(IF_ENTERPRISE){
                    def builds_enterprise=[:]
                    release_repo = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "dm", "tidb-lightning"]
                    if (RELEASE_TAG >= "v5.3.0") {
                        release_repo = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd",  "dm", "tidb-lightning", "ng-monitoring"]
                    }
                    for (item in release_repo) {
                        builds_enterprise["sync ${item} enterprise docker image"]={
                            def product = "${item}"
                            libs.retag_docker_image_for_ga(product, false)
                        }

                    }
                    parallel builds_enterprise
                }else{
                    def builds_community=[:]
                    release_repo = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "tidb-monitor-initializer", "dm", "tidb-lightning"]
                    if (RELEASE_TAG >= "v5.3.0") {
                        release_repo = ["dumpling", "br", "ticdc", "tidb-binlog", "tiflash", "tidb", "tikv", "pd", "tidb-monitor-initializer", "dm", "tidb-lightning", "ng-monitoring"]
                    }
                    for (item in release_repo) {
                        builds_community["sync ${item} community docker image"]={
                            def product = "${item}"
                            libs.retag_docker_image_for_ga(product, false)
                        }

                    }
                    parallel builds_community
                }

            }
        }
    }
}
