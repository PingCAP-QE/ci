/*
* @TIDB_TAG
* @TIKV_TAG
* @PD_TAG
* @BINLOG_TAG
* @TIFLASH_TAG
* @LIGHTNING_TAG
* @IMPORTER_TAG
* @TOOLS_TAG
* @BR_TAG
* @CDC_TAG
* @RELEASE_TAG
*/

def libs

def os = "linux"
def arch = "arm64"
def platform = "centos7"

catchError {
    stage('Prepare') {
        node('delivery') {
            container('delivery') {
                dir ('centos7') {

                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                    tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${TIDB_TAG} -s=${FILE_SERVER_URL}").trim()
                    tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${TIKV_TAG} -s=${FILE_SERVER_URL}").trim()
                    pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${PD_TAG} -s=${FILE_SERVER_URL}").trim()
                    tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${TIFLASH_TAG} -s=${FILE_SERVER_URL}").trim()

                    checkout scm
                    libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
                }
            }
        }
    }

    node('delivery') {
        container("delivery") {

            def builds = [:]

            builds["Push tidb Docker"] = {
                libs.release_online_image("tidb", tidb_sha1, arch,  os , platform,RELEASE_TAG, true)
            }

            builds["Push tikv Docker"] = {
                libs.release_online_image("tikv", tikv_sha1, arch,  os , platform,RELEASE_TAG, true)
            }

            builds["Push pd Docker"] = {
                libs.release_online_image("pd", pd_sha1, arch,  os , platform,RELEASE_TAG, true)
            }

            builds["Push tiflash Docker"] = {
                libs.release_online_image("tiflash", tiflash_sha1, arch,  os , platform,RELEASE_TAG, true)
            }

            builds["Push lightning Docker"] = {
                libs.retag_enterprise_docker("tidb-lightning", RELEASE_TAG)
            }

            builds["Push tidb-binlog Docker"] = {
                libs.retag_enterprise_docker("tidb-binlog", RELEASE_TAG)
            }

            builds["Push cdc Docker"] = {
                libs.retag_enterprise_docker("ticdc", RELEASE_TAG)
            }

            builds["Push br Docker"] = {
                libs.retag_enterprise_docker("br", RELEASE_TAG)
            }

            builds["Push dumpling Docker"] = {
                libs.retag_enterprise_docker("dumpling", RELEASE_TAG)
            }

            builds["Push NG monitoring Docker"] = {
                libs.retag_enterprise_docker("ng-monitoring", RELEASE_TAG)
            }

            stage("Push tarbll/image") {
                parallel builds
            }

        }
    }

    currentBuild.result = "SUCCESS"
}
