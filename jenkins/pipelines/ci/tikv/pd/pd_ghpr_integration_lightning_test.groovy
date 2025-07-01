if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tidb_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def TIKV_BRANCH = ghprbTargetBranch
def TIDB_BRANCH = ghprbTargetBranch

// parse tikv branch
def m1 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIKV_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIKV_BRANCH=${TIKV_BRANCH}"

// parse pd branch
def m2 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    TIDB_BRANCH = "${m2[0][1]}"
}
m2 = null

println "TIDB_BRANCH=${TIDB_BRANCH}"

try {
   stage('Integration Lightning Test') {
        node("master") {
            def pd_commit = ghprbTargetBranch
            if (ghprbPullId != "" || ghprbPullId == "0") {
                pd_commit = "pr/${ghprbPullId}"
            }
            def testParams = [
                string(name: 'ghprbTargetBranch', value: ghprbTargetBranch),
                string(name: 'ghprbPullId', value: '0'),
                string(name: "ghprbPullLink", value: ghprbPullLink),
                string(name: "ghprbPullTitle", value: ghprbPullTitle),
                string(name: "ghprbPullDescription", value: ghprbPullDescription),
                string(name: "ghprbActualCommit", value: "master"),
                string(name: "PD_BRANCH", value: pd_commit),
                string(name: "TIDB_BRANCH", value: TIDB_BRANCH),
                string(name: "TIKV_BRANCH", value: TIKV_BRANCH),
            ]
            build(job: "lightning_ghpr_test", parameters: testParams)
        }
    }
}
catch(Exception e) {
    stage("failed info") {
        println "click the Triggered Builds to get details of failed downstream job"
    }
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}
finally {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
    "${ghprbPullLink}" + "\n" +
    "${ghprbPullDescription}" + "\n" +
    "Integration DDL Test Result: `${currentBuild.result}`" + "\n" +
    "Elapsed Time: `${duration} mins` " + "\n" +
    "${env.RUN_DISPLAY_URL}"

    echo slackmsg
}
