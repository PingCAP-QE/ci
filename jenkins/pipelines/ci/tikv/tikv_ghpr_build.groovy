echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    echo "release test: ${params.containsKey("release_test")}"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tikv_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
def release="release"

def push_down_func_test_exist = false

try {
    node("build") {
        stage{"Checkout code"} {
            // checkout tikv
            dir("/home/jenkins/agent/git/tikv") {
                // if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                deleteDir()
                // }
                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:tikv/tikv.git']]]
            }
        }

        stage("Build") {
            def filepath = "builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
            def donepath = "builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/done"
            def refspath = "refs/pingcap/tikv/pr/${ghprbPullId}/sha1"
            if (params.containsKey("triggered_by_upstream_ci")) {
                refspath = "refs/pingcap/tidb/pr/branch-${ghprbTargetBranch}/sha1"
            }
            timestamps {
                dir("tikv") {
                    container("rust") {
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                        deleteDir()
                        timeout(30) {
                            sh """
                            set +e
                            curl --output /dev/null --silent --head --fail ${tikv_url}
                            if [ \$? != 0 ]; then
                                set -e
                                rm ~/.gitconfig || true
                                cp -R /home/jenkins/agent/git/tikv/. ./
                                git checkout -f ${ghprbActualCommit}
                                grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                                if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                                    echo using gcc 8
                                    source /opt/rh/devtoolset-8/enable
                                fi
                                CARGO_TARGET_DIR=/home/jenkins/agent/.target ROCKSDB_SYS_STATIC=1 make ${release}
                                # use make release
                                mkdir -p bin
                                cp /home/jenkins/agent/.target/release/tikv-server bin/
                                cp /home/jenkins/agent/.target/release/tikv-ctl bin/
                                tar czvf tikv-server.tar.gz bin/*
                                curl -F ${filepath}=@tikv-server.tar.gz ${FILE_SERVER_URL}/upload
                                echo "pr/${ghprbActualCommit}" > sha1
                                curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                                echo "done" > done
                                curl -F ${donepath}=@done ${FILE_SERVER_URL}/upload
                            """
                        }
                    }
                }
            }
        }

        deleteDir()
    }
    currentBuild.result = "SUCCESS"
}
catch(org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    currentBuild.result = "ABORTED"
}
catch(Exception e) {
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
            "Integration Common Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result == "FAILURE") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}

stage("upload status"){
    node("master"){
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
    }
}

