if (params.containsKey("release_test")) {
    echo "this build is triggered by qa for release testing"
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__binlog_commit)
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

node("${GO_TEST_SLAVE}") {
  def ws = pwd()
  deleteDir()

    dir("/home/jenkins/agent/git/tidb-binlog") {
      stage("Prepare"){
         println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

        if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
            deleteDir()
        }


        if (ghprbTargetBranch == "master"){
          echo "Target branch: ${ghprbTargetBranch}, load script from pr..."
          checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb-binlog.git']]]
          // 等到大多数 PR update  master 后可以取消下面的注释
           sh"""
           git checkout -f ${ghprbActualCommit}
           """
        }else{
          echo "Target branch: ${ghprbTargetBranch}, load script from master..."
          checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tidb-binlog.git']]]
        }
      }


        try{
          stage("Load groovy script"){
            def script_path = "scripts/groovy/${env.JOB_NAME}.groovy"
            println script_path
            sh"""
            wc -l ${script_path}
            """
            job = load script_path
          }
        }catch (Exception e) {
            currentBuild.result = "FAILURE"
            slackcolor = 'danger'
            echo "${e}"
        }

        stage('Summary') {

        }
    }
}
