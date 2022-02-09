// Author: Yixuan Zhao <zhaoyixuan (at) pingcap.com>


// def (TIKV_BRANCH, PD_BRANCH, TIDB_BRANCH) = ["master", "master", "master"]
def (TIKV_BRANCH, PD_BRANCH, TIDB_BRANCH) = [ghprbTargetBranch, ghprbTargetBranch, ghprbTargetBranch]

// parse tikv branch
def m1 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIKV_BRANCH = "${m1[0][1]}"
}
m1 = null

// parse pd branch
def m2 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    PD_BRANCH = "${m2[0][1]}"
}
m2 = null

// parse tidb branch
def m3 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_BRANCH = "${m3[0][1]}"
}
m3 = null

node("toolkit") {
    container('toolkit') {
        def basic_params = []
        def tidb_params = []
        def tikv_params = []
        def pd_params = []

        def tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
        def tikv_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1").trim()
        def pd_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1").trim()
        stage("Get commits and Set params") {

            println "TIDB_BRANCH=${TIDB_BRANCH}\nTIKV_BRANCH=${TIKV_BRANCH}\nPD_BRANCH=${PD_BRANCH}"
            println "tidb_sha1: $tidb_sha1\ntikv_sha1: $tikv_sha1\npd_sha1: $pd_sha1"


            ghprbCommentBody = ghprbCommentBody + " /tidb-test=pr/$ghprbPullId"            
            println "commentbody: $ghprbCommentBody"

            basic_params = [
                string(name: "upstreamJob", value: "tidb_test_ghpr_integration_test"),
                string(name: "ghprbCommentBody", value: ghprbCommentBody),
                string(name: "ghprbPullTitle", value: ghprbPullTitle),
                string(name: "ghprbPullLink", value: ghprbPullLink),
                string(name: "ghprbPullDescription", value: ghprbPullDescription)
            ]

            tidb_params = basic_params + [
                string(name: "ghprbTargetBranch", value: TIDB_BRANCH),
                string(name: "ghprbActualCommit", value: tidb_sha1)
            ]

            tikv_params = basic_params + [
                string(name: "ghprbTargetBranch", value: TIKV_BRANCH),
                string(name: "ghprbActualCommit", value: tikv_sha1)
            ]

            pd_params = basic_params + [
                string(name: "ghprbTargetBranch", value: PD_BRANCH),
                string(name: "ghprbActualCommit", value: pd_sha1),
            ]
        }

        stage("copy files"){
            // 由于 ghpr 产生的包来自 pr ，储存路径为 builds/pingcap/tidb/pr/COMMIT ，而这里我们的 commit 是从 branch 上取的，tar 包的位置在 builds/pingcap/tidb/COMMIT
            // 下游集成测试会从 pr/COMMIT 路径下载包，就会导致 not found
            // 这里 参照 qa_release_test 做个 hack,拷贝相关包到对应路径,  tikv 同理

            sh """
            wget ${FILE_SERVER_URL}/download/builds/pingcap/tidb/$tidb_sha1/centos7/tidb-server.tar.gz
            curl -F builds/pingcap/tidb/pr/${tidb_sha1}/centos7/tidb-server.tar.gz=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload
            rm -f tidb-server.tar.gz
            inv upload --dst builds/pingcap/tidb/pr/${tidb_sha1}/centos7/done --content done

            inv upload --dst builds/download/refs/pingcap/tikv/${tikv_sha1}/sha1 --content $tikv_sha1
            inv upload --dst builds/download/refs/pingcap/pd/$pd_sha1/sha1 --content $pd_sha1


            """
            
            // sh "inv upload --dst builds/download/refs/pingcap/tidb/${params.TIDB_COMMIT}/sha1 --content ${params.TIDB_COMMIT}"
            // sh "inv upload --dst builds/download/refs/pingcap/tikv/${params.TIKV_COMMIT}/sha1 --content ${params.TIKV_COMMIT}"
            // sh "inv upload --dst builds/download/refs/pingcap/pd/${params.PD_COMMIT}/sha1 --content ${params.PD_COMMIT}"


        }
        println tidb_params

        stage("trigger jobs"){
            // parallel(
                build(job: "tidb_ghpr_integration_common_test", wait: false, parameters: tidb_params)
                build(job: "tidb_ghpr_integration_ddl_test", wait: false, parameters: tidb_params)
                build(job: "tidb_ghpr_integration_campatibility_test", wait: false,parameters: tidb_params)
            // )
        }

        stage("Summary"){
        }
    }

}