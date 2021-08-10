// def ghprbTargetBranch = "master"
// def ghprbCommentBody = ""
// def ghprbPullId = ""
// def ghprbPullTitle = ""
// def ghprbPullLink = ""
// def ghprbPullDescription = ""

if (params.containsKey("triggered_by_build_br_multi_branch")) {
    // Triggered by `build_br_multi_branch`.
    // It contains keys:
    //     booleanParam(name: 'force', value: true),
    //     booleanParam(name: 'triggered_by_build_br_multi_branch', value: true),
    //     string(name: 'build_br_multi_branch_release_branch', value: "master"),
    //     string(name: 'build_br_multi_branch_ghpr_actual_commit', value: "${githash}"),
    echo "br branch push test: ${params.containsKey("triggered_by_build_br_multi_branch")}"
    ghprbTargetBranch = params.getOrDefault("build_br_multi_branch_ghpr_target_branch", params.build_br_multi_branch_release_branch)
    ghprbActualCommit = params.getOrDefault("build_br_multi_branch_ghpr_actual_commit", params.build_br_multi_branch_br_commit)
    ghprbCommentBody = ""
    ghprbPullId = ""
    ghprbPullTitle = ""
    ghprbPullLink = ""
    ghprbPullDescription = ""
}

if (params.containsKey("triggered_by_upstream_pr_ci")) {
    // Triggered by upstream (TiDB/TiKV/PD) PR.
    // It contains keys:
    //     booleanParam(name: 'force', value: true),
    //     string(name: 'upstream_pr_ci', value: "tidb"),
    //     string(name: 'upstream_pr_ci_ghpr_target_branch', ghprbTargetBranch),
    //     string(name: 'upstream_pr_ci_ghpr_actual_commit', ghprbActualCommit),
    //     string(name: 'upstream_pr_ci_ghpr_pull_id', ghprbPullId),
    //     string(name: 'upstream_pr_ci_ghpr_pull_title', ghprbPullTitle),
    //     string(name: 'upstream_pr_ci_ghpr_pull_link', ghprbPullLink),
    //     string(name: 'upstream_pr_ci_ghpr_pull_description', ghprbPullDescription),
    //     string(name: 'upstream_pr_ci_override_tidb_download_link', tidb_url),
    //     string(name: 'upstream_pr_ci_override_tikv_download_link', tikv_url),
    //     string(name: 'upstream_pr_ci_override_pd_download_link', pd_url),
    echo "upstream pr test: ${params.containsKey("triggered_by_upstream_pr_ci")}"
    ghprbTargetBranch = params.getOrDefault("upstream_pr_ci_ghpr_target_branch", params.upstream_pr_ci_release_branch)
    ghprbActualCommit = params.getOrDefault("upstream_pr_ci_ghpr_actual_commit", params.upstream_pr_ci_br_commit)
    ghprbCommentBody = ""
    ghprbPullId = params.getOrDefault("upstream_pr_ci_ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("upstream_pr_ci_ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("upstream_pr_ci_ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("upstream_pr_ci_ghpr_pull_description", "")
}

if (params.containsKey("release_test")) {
    echo "release test: ${params.containsKey("release_test")}"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", "")
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", "")
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

// Remove tailing string.
@NonCPS
def getTargetBranch(body){
    def m1 = body =~ /release-[0-9]\.[0-9]/
    if (m1) {
        return "${m1[0]}"
    }
    return ""
}
if (getTargetBranch(ghprbTargetBranch)!=""){
    ghprbTargetBranch=getTargetBranch(ghprbTargetBranch)
} else {
    ghprbTargetBranch = "master"
}


def TIKV_BRANCH = ghprbTargetBranch
def TIKV_IMPORTER_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch
def TIDB_BRANCH = ghprbTargetBranch
def BUILD_NUMBER = "${env.BUILD_NUMBER}"
def tiflashBranch = TIKV_BRANCH
def tiflashCommit = ""
def CDC_BRANCH = ""

if (ghprbTargetBranch == "master")  {
    TIKV_IMPORTER_BRANCH = "release-5.0"
}

if (ghprbTargetBranch == "master" || (ghprbTargetBranch.startsWith("release-") && ghprbTargetBranch >= "release-4.0")) {
    CDC_BRANCH = ghprbTargetBranch
}

if (params.containsKey("release_test")) {
    TIKV_BRANCH = params.release_test__release_branch
    PD_BRANCH = params.release_test__release_branch
    TIDB_BRANCH = params.release_test__release_branch
    CDC_BRANCH = params.release_test__release_branch
    tiflashCommit = params.getOrDefault("release_test__tiflash_commit", "")
    tiflashBranch = params.release_test__release_branch
    ghprbTargetBranch = params.release_test__release_branch
    // Override BR commit hash
    ghprbActualCommit = params.getOrDefault("release_test__br_commit", "")
}

// parse tikv branch
@NonCPS
def getTikvBranch(body){
    def m1 = body =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m1) {
        return "${m1[0][1]}"
    }
    return ""
}
if (getTikvBranch(ghprbCommentBody)!=""){
    TIKV_BRANCH=getTikvBranch(ghprbCommentBody)
}
println "TIKV_BRANCH=${TIKV_BRANCH}"

// parse pd branch
@NonCPS
def getPDBranch(body){
    def m1 = body =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m1) {
        return "${m1[0][1]}"
    }
    return ""
}
if (getPDBranch(ghprbCommentBody)!=""){
    PD_BRANCH=getPDBranch(ghprbCommentBody)
}
println "PD_BRANCH=${PD_BRANCH}"

// parse tidb branch
@NonCPS
def getTidbBranch(body){
    def m1 = body =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m1) {
        return "${m1[0][1]}"
    }
    return ""
}
if (getTidbBranch(ghprbCommentBody)!=""){
    TIDB_BRANCH=getTidbBranch(ghprbCommentBody)
}
println "TIDB_BRANCH=${TIDB_BRANCH}"

// parse cdc branch
@NonCPS
def getCdcBranch(body){
    def m1 = body =~ /ticdc\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m1) {
        return "${m1[0][1]}"
    }
    return ""
}
if (getCdcBranch(ghprbCommentBody)!=""){
    CDC_BRANCH=getCdcBranch(ghprbCommentBody)
}
println "CDC_BRANCH=${CDC_BRANCH}"

// parse tikv-importer branch
@NonCPS
def getImporterBranch(body){
    def m1 = body =~ /importer\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m1) {
        return "${m1[0][1]}"
    }
    return ""
}
if (getImporterBranch(ghprbCommentBody)!=""){
    TIKV_IMPORTER_BRANCH=getImporterBranch(ghprbCommentBody)
}
println "TIKV_IMPORTER_BRANCH=${TIKV_IMPORTER_BRANCH}"

@NonCPS
boolean isMoreRecentOrEqual( String a, String b ) {
    if (a == b) {
        return true
    }

    [a,b]*.tokenize('.')*.collect { it as int }.with { u, v ->
       Integer result = [u,v].transpose().findResult{ x,y -> x <=> y ?: null } ?: u.size() <=> v.size()
       return (result == 1)
    } 
}

string trimPrefix = {
        it.startsWith('release-') ? it.minus('release-') : it 
    }

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = false
releaseBranchUseGo1160 = "release-5.1"

if (!isNeedGo1160) {
    isNeedGo1160 = isBranchMatched(["master", "hz-poc"], ghprbTargetBranch)
}
if (!isNeedGo1160 && ghprbTargetBranch.startsWith("release-")) {
    isNeedGo1160 = isMoreRecentOrEqual(trimPrefix(ghprbTargetBranch), trimPrefix(releaseBranchUseGo1160))
    if (isNeedGo1160) {
        println "targetBranch=${ghprbTargetBranch}  >= ${releaseBranchUseGo1160}"
    }
}

if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

def boolean isBRMergedIntoTiDB() {
    return params.getOrDefault("triggered_by_upstream_pr_ci", "Origin") == "tidb-br"
}

def get_commit_hash(prj, branch_or_hash) {
    if (branch_or_hash.length() == 40) {
        return branch_or_hash
    }

    def url = "${FILE_SERVER_URL}/download/refs/pingcap/${prj}/${branch_or_hash}/sha1"
    def hash = sh(returnStdout: true, script: """
        while ! curl --output /dev/null --silent --head --fail ${url}; do sleep 5; done
        curl ${url}
    """).trim()
    return hash
}

def run_unit_test() {
    node("${GO_TEST_SLAVE}") {
        container("golang") {
            println "debug command:\nkubectl -n jenkins-tidb exec -ti ${NODE_NAME} bash"
            def ws = pwd()
            deleteDir()

            // unstash 'br'
            timeout(30) {
                sh label: "Go Version", script: """
                go version
                """

                sh label: "Run unit tests", script: """
                mkdir -p ${ws}/go/src/github.com/pingcap/br/
                cd ${ws}/go/src/github.com/pingcap/br

                curl ${FILE_SERVER_URL}/download/builds/pingcap/br/pr/${ghprbActualCommit}/centos7/br_integration_test.tar.gz | tar xz

                PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make check test

                rm -rf /tmp/backup_restore_test
                mkdir -p /tmp/backup_restore_test
                rm -rf cover
                mkdir cover

                export GOPATH=\$GOPATH:${ws}/go
                export PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH
                make tools testcover

                # Must move coverage files to the current directory
                ls /tmp/backup_restore_test
                cp /tmp/backup_restore_test/cov.* cover/ || true
                ls cover
                """
            }

            stash includes: "go/src/github.com/pingcap/br/cover/**", name: "unit_test", useDefaultExcludes: false
        }
    }
}

def run_integration_tests(case_names, tidb, tikv, pd, cdc, importer, tiflashBranch, tiflashCommit) {
    node("${GO_TEST_SLAVE}") {
        container("golang") {
            println "debug command:\nkubectl -n jenkins-tidb exec -ti ${NODE_NAME} bash"
            def ws = pwd()
            deleteDir()

            // unstash 'br'

            timeout(30) {
                scripts_builder = new StringBuilder()

                scripts_builder.append("mkdir -p ${ws}/go/src/github.com/pingcap/br/bin\n")
                scripts_builder.append("cd ${ws}/go/src/github.com/pingcap/br\n")

                // br_integration_test
                def from = params.getOrDefault("triggered_by_upstream_pr_ci", "Origin")
                def get_tidb_from_local = false
                def commit_id = "${ghprbActualCommit}"
                switch (from) {
                    case "tikv":
                        def download_url = "?/pr/tikv_unkown_commit_id/?"
                        download_url = params.getOrDefault("upstream_pr_ci_override_tikv_download_link", download_url)
                        def index_begin = download_url.indexOf("pr/") + 3
                        def index_end = download_url.indexOf("/", index_begin)
                        commit_id = "tikv_" + download_url.substring(index_begin, index_end)
                        break;
                    case "tidb":
                        def download_url = "?/pr/tidb_unkown_commit_id/?"
                        download_url = params.getOrDefault("upstream_pr_ci_override_tidb_download_link", download_url)
                        def index_begin = download_url.indexOf("pr/") + 3
                        def index_end = download_url.indexOf("/", index_begin)
                        commit_id = "tidb_" + download_url.substring(index_begin, index_end)
                        break;
                    case "pd":
                        def download_url = "?/pr/pd_unkown_commit_id/?"
                        download_url = params.getOrDefault("upstream_pr_ci_override_pd_download_link", download_url)
                        def index_begin = download_url.indexOf("pr/") + 3
                        def index_end = download_url.indexOf("/", index_begin)
                        commit_id = "pd_" + download_url.substring(index_begin, index_end)
                        break;

                }
                scripts_builder.append("(curl ${FILE_SERVER_URL}/download/builds/pingcap/br/pr/${commit_id}/centos7/br_integration_test.tar.gz | tar xz;) &\n")

                // cdc
                if (cdc != "") {
                    scripts_builder.append("(cdc_sha1=\$(curl ${FILE_SERVER_URL}/download/refs/pingcap/ticdc/${cdc}/sha1); ")
                                .append("curl ${FILE_SERVER_URL}/download/builds/pingcap/ticdc/\${cdc_sha1}/centos7/ticdc-linux-amd64.tar.gz | tar xz ticdc-linux-amd64/bin/cdc; ")
                                .append("mv ticdc-linux-amd64/bin/* bin/; ")
                                .append("rm -rf ticdc-linux-amd64/;) &\n")
                }

                // tikv
                scripts_builder.append("(tikv_sha1=\$(curl ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${tikv}/sha1); ")
                def tikv_download_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"
                if (params.containsKey("upstream_pr_ci_override_tikv_download_link")) {
                    tikv_download_url = params.getOrDefault("upstream_pr_ci_override_tikv_download_link", tikv_download_url)
                }

                scripts_builder.append("curl ${tikv_download_url} | tar xz bin/tikv-server bin/tikv-ctl;) &\n")

                // tikv-importer
                scripts_builder.append("(tikv_importer_sha1=\$(curl ${FILE_SERVER_URL}/download/refs/pingcap/importer/${importer}/sha1); ")
                            .append("curl ${FILE_SERVER_URL}/download/builds/pingcap/importer/\${tikv_importer_sha1}/centos7/importer.tar.gz | tar xz bin/tikv-importer;) &\n")

                // pd & pd-ctl
                scripts_builder.append("(pd_sha1=\$(curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/${pd}/sha1); ")
                            .append("mkdir pd-source; ")
                def pd_download_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/\${pd_sha1}/centos7/pd-server.tar.gz"
                if (params.containsKey("upstream_pr_ci_override_pd_download_link")) {
                    pd_download_url = params.getOrDefault("upstream_pr_ci_override_pd_download_link", pd_download_url)
                }

                scripts_builder.append("curl ${pd_download_url} | tar -xz -C pd-source; ")
                            .append("cp pd-source/bin/* bin/; rm -rf pd-source;) &\n")

                // tidb
                // we build tidb-server from local, then put it into br_integration_test.tar.gz
                // so we can get it from br_integration_test.tar.gz
                if (!isBRMergedIntoTiDB()) {
                    echo "br in tidb"
                    scripts_builder.append("(tidb_sha1=\$(curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${tidb}/sha1); ")
                            .append("mkdir tidb-source; ")
                    def tidb_download_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/\${tidb_sha1}/centos7/tidb-server.tar.gz"
                    if (params.containsKey("upstream_pr_ci_override_tidb_download_link")) {
                        tidb_download_url = params.getOrDefault("upstream_pr_ci_override_tidb_download_link", tidb_download_url)
                    }

                    scripts_builder.append("curl ${tidb_download_url} | tar -xz -C tidb-source; ")
                            .append("cp tidb-source/bin/tidb-server bin/; rm -rf tidb-source;) &\n")
                } else {
                    scripts_builder.append("\n")
                }

                // tiflash
                if (tiflashCommit == "") {
                    scripts_builder.append("(tiflashCommit=\$(curl ${FILE_SERVER_URL}/download/refs/pingcap/tiflash/${tiflashBranch}/sha1); ")
                } else {
                    scripts_builder.append("(tiflashCommit=${tiflashCommit}; ")
                }

                scripts_builder.append("curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/${tiflashBranch}/\${tiflashCommit}/centos7/tiflash.tar.gz | tar xz tiflash; ")
                            .append("mv tiflash/* bin/; rmdir tiflash;) &\n")

                // Testing S3 ans GCS.
                // go-ycsb are manual uploaded for test br
                // minio and s3cmd for testing s3
                // download kes server
                // fake-gcs-server for testing gcs
                // br v4.0.8 for testing gcs incompatible test
                scripts_builder.append("curl ${FILE_SERVER_URL}/download/builds/pingcap/go-ycsb/test-br/go-ycsb -o bin/go-ycsb && chmod 777 bin/go-ycsb\n")
                            .append("curl ${FILE_SERVER_URL}/download/builds/minio/minio/RELEASE.2020-02-27T00-23-05Z/minio -o bin/minio && chmod 777 bin/minio\n")
                            .append("curl ${FILE_SERVER_URL}/download/builds/minio/minio/RELEASE.2020-02-27T00-23-05Z/mc -o bin/mc && chmod 777 bin/mc\n")
                            .append("curl ${FILE_SERVER_URL}/download/kes -o bin/kes && chmod 777 bin/kes\n")
                            .append("curl ${FILE_SERVER_URL}/download/builds/fake-gcs-server -o bin/fake-gcs-server && chmod 777 bin/fake-gcs-server\n")
                            .append("curl ${FILE_SERVER_URL}/download/builds/brv4.0.8 -o bin/brv4.0.8 && chmod 777 bin/brv4.0.8\n")
                            .append("wait\n")
                            .append("go version")

                def scripts = scripts_builder.toString()
                echo scripts
                sh label: "Download and Go Version", script: scripts
            }

            dir("${ws}/go/src/github.com/pingcap/br") {
                export_cmd = ""
                if (isBRMergedIntoTiDB()) {
                    // move tests outside for compatibility
                    sh label: "Move test outside", script: """
                    mv br/tests/* tests/
                    """
                    // lightning_examples test need mv file from examples pkg.
                    // so we should overwrite the path.
                    export_cmd = "export EXAMPLES_PATH=br/pkg/lightning/mydump/examples"
                }
                for (case_name in case_names) {
                      timeout(120) {
                          try {
                              sh label: "Running ${case_name}", script: """

                              rm -rf /tmp/backup_restore_test
                              mkdir -p /tmp/backup_restore_test
                              rm -rf cover
                              mkdir cover

                              if [[ ! -e tests/${case_name}/run.sh ]]; then
                                  echo ${case_name} not exists, skip.
                                  exit 0
                              fi

                              export GOPATH=\$GOPATH:${ws}/go
                              export PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH

                              ls /tmp/backup_restore_test

                              ${export_cmd}
                              TEST_NAME=${case_name} tests/run.sh

                              # Must move coverage files to the current directory
                              ls /tmp/backup_restore_test
                              cp /tmp/backup_restore_test/cov.* cover/ || true
                              ls cover
                              """
                          } catch (Exception e) {
                              sh "tail -4000 '/tmp/backup_restore_test/pd.log' || true"
                              sh "tail -40000 '/tmp/backup_restore_test/tikv1.log' || true"
                              sh "tail -40000 '/tmp/backup_restore_test/tikv2.log' || true"
                              sh "tail -40000 '/tmp/backup_restore_test/tikv3.log' || true"
                              sh "tail -40000 '/tmp/backup_restore_test/tikv4.log' || true"
                              sh "tail -1000 '/tmp/backup_restore_test/tidb.log' || true"
                              sh "tail -1000 '/tmp/backup_restore_test/tiflash-manager.log' || true"
                              sh "tail -1000 '/tmp/backup_restore_test/tiflash-stdout.log' || true"
                              sh "tail -1000 '/tmp/backup_restore_test/tiflash-stderr.log' || true"
                              sh "tail -1000 '/tmp/backup_restore_test/tiflash-proxy.log' || true"
                              sh "cat '/tmp/backup_restore_test/importer.log' || true"
                              sh "find '/tmp/backup_restore_test/' -name \"lightning*log\" -exec echo \">>>>>>>>> {}\" \\; -exec cat {} \\; || true"
                              sh "echo 'Test failed'"
                              throw e;
                          }
                      }
                      // stash "cover/**" as we are already in "go/src/github.com/pingcap/br/".
                      stash includes: "cover/**", name: "integration_test_${case_name}", useDefaultExcludes: false, allowEmpty: true
                 }
            }
        }
    }
}

def make_parallel_jobs(case_names, batch_size, tidb, tikv, pd, cdc, importer, tiflashBranch, tiflashCommit) {
    def batches = []
    case_names.collate(batch_size).each { names ->
        batches << [names, {
            run_integration_tests(names, tidb, tikv, pd, cdc, importer, tiflashBranch, tiflashCommit)
        }]
    }
    return batches
}

catchError {
    def test_case_names = []
    // >= 2m 30s
    def very_slow_case_names = [
        "br_s3",    // 4m 3s
        "br_tiflash",   // 2m 42s
        "br_tikv_outage",   // 2m 39s
        "br_tikv_outage2",  // 4m 40s
        "lightning_disk_quota"  // 3m 9s
    ]
    // >= 1m 20s
    def slow_case_names = [
        "br_300_small_tables",  // 1m 47s
        "br_full_ddl",  // 1m 19s
        "br_incompatible_tidb_config", // 1m 39s
        "br_log_restore",   // 2m 15s
        "lightning_checkpoint", // 2m 0s
        "lightning_new_collation" // 1m 28s
    ]
    // > 50s
    def little_slow_case_names = [
        "br_table_filter",
        "br_systables", // 1m 7s
        "br_rawkv", // 1m 15s
        "br_key_locked", // 1m 8s
        "br_other", // 1m 10s
        "br_history", // 1m 7s
        "br_full", // 1m 14s
        "br_full_index", // 1m 0s
    ]

    stage('Prepare') {
        node("${GO_BUILD_SLAVE}") {
            container("golang") {
                println "debug command:\nkubectl -n jenkins-tidb exec -ti ${NODE_NAME} bash"
                def ws = pwd()
                deleteDir()

                def paramstring = ""
                params.each{ k, v -> paramstring += "\n\t${k}:${v}" }

                println "params: ${paramstring}"
                println "ghprbPullId: ${ghprbPullId}"

                def from = params.getOrDefault("triggered_by_upstream_pr_ci", "Origin")
                def git_repo_url = "git@github.com:pingcap/br.git"
                def build_br_cmd = "make build_for_integration_test"
                def commit_id = "${ghprbActualCommit}"
                switch (from) {
                    case "tikv":
                        def download_url = "?/pr/tikv_unkown_commit_id/?"
                        download_url = params.getOrDefault("upstream_pr_ci_override_tikv_download_link", download_url)
                        def index_begin = download_url.indexOf("pr/") + 3
                        def index_end = download_url.indexOf("/", index_begin)
                        commit_id = "tikv_" + download_url.substring(index_begin, index_end)
                        break;
                    case "tidb":
                        def download_url = "?/pr/tidb_unkown_commit_id/?"
                        download_url = params.getOrDefault("upstream_pr_ci_override_tidb_download_link", download_url)
                        def index_begin = download_url.indexOf("pr/") + 3
                        def index_end = download_url.indexOf("/", index_begin)
                        commit_id = "tidb_" + download_url.substring(index_begin, index_end)
                        break;
                    case "pd":
                        def download_url = "?/pr/pd_unkown_commit_id/?"
                        download_url = params.getOrDefault("upstream_pr_ci_override_pd_download_link", download_url)
                        def index_begin = download_url.indexOf("pr/") + 3
                        def index_end = download_url.indexOf("/", index_begin)
                        commit_id = "pd_" + download_url.substring(index_begin, index_end)
                        break;
                }

                // Checkout and build testing binaries.
                dir("${ws}/go/src/github.com/pingcap/br") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }

                    def filepath = "builds/pingcap/br/pr/${commit_id}/centos7/br_integration_test.tar.gz"

                    // This happened after BR merged into TiDB.
                    if (isBRMergedIntoTiDB()) {
                        println "fast checkout tidb repo"
                        git_repo_url = "git@github.com:pingcap/tidb.git"
                        build_br_cmd = "make build_for_br_integration_test && make server"

                        // copy code from nfs cache
                        if(fileExists("/nfs/cache/git-test/src-tidb.tar.gz")){
                            timeout(5) {
                            sh """
                            cp -R /nfs/cache/git-test/src-tidb.tar.gz*  ./
                            mkdir -p ${ws}/go/src/github.com/pingcap/br
                            tar -xzf src-tidb.tar.gz -C ${ws}/go/src/github.com/pingcap/br --strip-components=1
                            """
                            }
                        }
                        if(!fileExists("${ws}/go/src/github.com/pingcap/br/Makefile")) {
                            sh """
                            rm -rf /home/jenkins/agent/code-archive/tidb.tar.gz
                            rm -rf /home/jenkins/agent/code-archive/tidb
                            wget -O /home/jenkins/agent/code-archive/tidb.tar.gz  ${FILE_SERVER_URL}/download/source/tidb.tar.gz -q
                            tar -xzf /home/jenkins/agent/code-archive/tidb.tar.gz -C ./ --strip-components=1
                            """
                        }
                    }
                    specStr = "+refs/pull/*:refs/remotes/origin/pr/*"
                    if (ghprbPullId != null && ghprbPullId != "") {
                        specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
                    }

                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: git_repo_url]]]

                    sh label: "Build and Compress testing binaries", script: """
                    git checkout -f ${ghprbActualCommit}
                    git rev-parse HEAD

                    go version

                    ${build_br_cmd}

                    tar czf br_integration_test.tar.gz * .[!.]*
                    curl -F ${filepath}=@br_integration_test.tar.gz ${FILE_SERVER_URL}/upload
                    """

                    // Collect test case names.
                    switch (from) {
                        case "tikv":
                            test_case_names = [
                                "br_full",
                                "br_gcs",
                                "br_s3",
                                "lightning_alter_random",
                                "lightning_new_collation",
                                "lightning_row-format-v2",
                                "lightning_s3",
                                "lightning_sqlmode",
                                "lightning_tiflash",
                            ]
                            very_slow_case_names = []
                            slow_case_names = []
                            little_slow_case_names = []
                            break;
                        case "tidb":
                            test_case_names = [
                                "br_incremental_ddl",
                                "br_incompatible_tidb_config",
                                "br_log_restore",
                                "lightning_alter_random",
                                "lightning_new_collation",
                                "lightning_row-format-v2",
                                "lightning_s3",
                                "lightning_sqlmode",
                                "lightning_tiflash",
                            ]
                            very_slow_case_names = []
                            slow_case_names = []
                            little_slow_case_names = []
                            break;
                        case "pd":
                            test_case_names = [
                                "br_other",
                                "br_split_region_fail",
                                "lightning_alter_random",
                                "lightning_new_collation",
                                "lightning_row-format-v2",
                                "lightning_s3",
                                "lightning_sqlmode",
                                "lightning_tiflash",
                            ]
                            very_slow_case_names = []
                            slow_case_names = []
                            little_slow_case_names = []
                            break;
                        default:
                            list_path = "tests"
                            if (isBRMergedIntoTiDB()) {
                                // current gcs test need custom endpoint
                                // but official client has a bug on custom endpoint
                                // in origin BR repo: we use replace go.mod to fix this issue.
                                // but after BR merged into TiDB, we cannot use replace due to the plugin check.
                                // so we should skip these tests until https://github.com/googleapis/google-cloud-go/pull/3509 merged.
                                list_path = "br/tests | grep -v br_gcs | grep -v lightning_gcs"
                            }
                            def list = sh(script: "ls ${list_path} | grep -E 'br_|lightning_'", returnStdout:true).trim()
                            for (name in list.split("\\n")) {
                                test_case_names << name
                            }
                            // filter out the nonexistent tests
                            very_slow_case_names = very_slow_case_names - (very_slow_case_names - test_case_names)
                            slow_case_names = slow_case_names - (slow_case_names - test_case_names)
                            little_slow_case_names = little_slow_case_names - (little_slow_case_names - test_case_names)
                    }
                }
            }
        }
    }

    stage("Unit/Integration Test") {
        def test_cases = [:]
        
        if (!params.containsKey("triggered_by_upstream_pr_ci")) {
            // Add unit tests
            test_cases["unit test"] = {
                run_unit_test()
            }
        }
        if (!very_slow_case_names.isEmpty()) {
            make_parallel_jobs(
                very_slow_case_names, 1,
                TIDB_BRANCH, TIKV_BRANCH, PD_BRANCH, CDC_BRANCH, TIKV_IMPORTER_BRANCH, tiflashBranch, tiflashCommit
            ).each { v ->
                test_cases["(very slow) ${v[0][0]}"] = v[1]
            }
        }
        if (!slow_case_names.isEmpty()) {
            // Add slow integration tests
            make_parallel_jobs(
                    slow_case_names, 2,
                    TIDB_BRANCH, TIKV_BRANCH, PD_BRANCH, CDC_BRANCH, TIKV_IMPORTER_BRANCH, tiflashBranch, tiflashCommit
            ).each { v ->
                test_cases["(slow) ${v[0][0]} ~ ${v[0][-1]}"] = v[1]
            }
        }
        if (!little_slow_case_names.isEmpty()) {
            make_parallel_jobs(
                    little_slow_case_names, 3,
                    TIDB_BRANCH, TIKV_BRANCH, PD_BRANCH, CDC_BRANCH, TIKV_IMPORTER_BRANCH, tiflashBranch, tiflashCommit
            ).each { v ->
                test_cases["(little slow) ${v[0][0]} ~ ${v[0][-1]}"] = v[1]
            }
        }
        
        // Add rest integration tests
        test_case_names -= slow_case_names
        test_case_names -= very_slow_case_names
        test_case_names -= little_slow_case_names
        // TODO: limit parallel size
        def batch_size = (18 + test_case_names.size()).intdiv(19)
        println batch_size
        make_parallel_jobs(
                test_case_names, batch_size,
                TIDB_BRANCH, TIKV_BRANCH, PD_BRANCH, CDC_BRANCH, TIKV_IMPORTER_BRANCH, tiflashBranch, tiflashCommit
        ).each { v ->
            test_cases["${v[0][0]} ~ ${v[0][-1]}"] = v[1]
        }

        println test_cases
        test_cases.failFast = true
        parallel test_cases
    }

    stage('Coverage') {
        // Skip upload coverage when the job is initialed by TiDB PRs.
        node("${GO_TEST_SLAVE}") {
            container("golang") {
                println "debug command:\nkubectl -n jenkins-tidb exec -ti ${NODE_NAME} bash"
                if (params.containsKey("triggered_by_upstream_pr_ci")) {
                    println "skip uploading coverage as it is triggered by upstream PRs"
                } else {
                    def ws = pwd()
                    deleteDir()

                    // unstash 'br'
                    // "unit_test" is stashed under home folder, unstash here
                    // to restores coverage files to "go/src/github.com/pingcap/br/cover"
                    unstash 'unit_test'
                    dir("${ws}/go/src/github.com/pingcap/br") {
                        // "integration_test_${case_name}" is stashed under
                        // ""go/src/github.com/pingcap/br"", unstash here
                        // to restores coverage files to "cover"
                        test_case_names.each{ case_name ->
                            unstash "integration_test_${case_name}"
                        }

                        container("golang") {
                            withCredentials([string(credentialsId: 'codecov-token-br', variable: 'CODECOV_TOKEN')]) {
                                timeout(60) {
                                    sh label: "Calculate coverage", script: """
                                    ls cover
                                    GO111MODULE=off go get github.com/wadey/gocovmerge
                                    gocovmerge cover/cov.* > coverage.txt

                                    echo ${CODECOV_TOKEN} > CODECOV_TOKEN
                                    curl -s https://codecov.io/bash | bash -s - -t @CODECOV_TOKEN
                                    """
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    currentBuild.result = "SUCCESS"
}

stage('Summary') {
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "BRIE Integration Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"

        // Also send to #jenkins-ci-migration if tests triggered by upstream pr fails.
        if (params.containsKey("triggered_by_upstream_pr_ci") && currentBuild.result != "ABORTED") {
            slackmsg = ":scream_cat: " + slackmsg
            slackSend channel: '#jenkins-ci-migration', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
    }
}
