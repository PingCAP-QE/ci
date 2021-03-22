def tiup_desc = ""
def br_desc = "TiDB/TiKV cluster backup restore tool"

def br_sha1, tarball_name, dir_name

node("build_go1130") {
    container("golang") {
        stage("Prepare") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            deleteDir()
        }

        def util = load "tiup_utils.groovy"

        stage("Install tiup/qshell") {
            util.install_tiup "/usr/local/bin", ${PINGCAP_PRIV_KEY}
            util.install_qshell "/usr/local/bin", ${QSHELL_KEY}, ${QSHELL_SEC}
        }

        if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v3.1.0") {
            stage("Get hash") {
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                if (RELEASE_TAG == "nightly") {
                    tag = "master"
                } else {
                    tag = RELEASE_TAG
                }

                br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            }

            stage("tiup release br linux amd64") {
                util.update "br", RELEASE_TAG, "linux", "amd64", TIDB_VERSION
            }

            stage("tiup release br linux arm64") {
                util.update "br", RELEASE_TAG, "linux", "arm64", TIDB_VERSION
            }

            stage("tiup release br darwin amd64") {
                util.update "br", RELEASE_TAG, "darwin", "amd64", TIDB_VERSION
            }
        }
    }
}