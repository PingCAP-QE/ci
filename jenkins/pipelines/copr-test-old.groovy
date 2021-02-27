def TIDB_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch
def COPR_TEST_BRANCH = "master"


// parse tidb branch
def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIDB_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

// parse pd branch
def m2 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    PD_BRANCH = "${m2[0][1]}"
}
m2 = null
println "PD_BRANCH=${PD_BRANCH}"

// parse copr-test branch
def m3 = ghprbCommentBody =~ /copr[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    COPR_TEST_BRANCH = "${m3[0][1]}"
}
m3 = null
println "COPR_TEST_BRANCH=${COPR_TEST_BRANCH}"

def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
def release = "release"

def specStr = "+refs/pull/*:refs/remotes/origin/pr/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

try {
    stage('Build') {
        node("build") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            // checkout tikv
            dir("/home/jenkins/agent/git/tikv") {
                if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }
                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:tikv/tikv.git']]]
            }

            def filepath = "builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
            def refspath = "refs/pingcap/tikv/pr/${ghprbPullId}/sha1"

            dir("tikv") {
                container("rust") {
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
                                source scl_source enable devtoolset-8
                            fi
                            CARGO_TARGET_DIR=/home/jenkins/.target ROCKSDB_SYS_STATIC=1 make ${release}
                            mkdir bin
                            cp /home/jenkins/.target/release/tikv-server bin/
                            tar czvf tikv-server.tar.gz bin/*
                            curl -F ${filepath}=@tikv-server.tar.gz ${FILE_SERVER_URL}/upload
                            echo "pr/${ghprbActualCommit}" > sha1
                            curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        else
                            set -e
                            (curl ${tikv_url} | tar xz) || (sleep 15 && curl ${tikv_url} | tar xz)
                        fi
                        """
                    }
                }
            }

            stash includes: "tikv/bin/**", name: "tikv"
            deleteDir()
        }
    }


    node("${GO_TEST_SLAVE}") {
        def ws = pwd()
        
        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

        stage('Prepare') {
            dir("copr-test") {
                timeout(30) {
                    checkout(changelog: false, poll: false, scm: [
                        $class: "GitSCM",
                        branches: [ [ name: COPR_TEST_BRANCH ] ],
                        userRemoteConfigs: [ [ url: 'https://github.com/tikv/copr-test.git',
                                            refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*' ] ],
                        extensions: [ [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'] ],
                    ])
                }
            }
            container("golang") {
                dir("pd") {
                    deleteDir()
                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                    def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                    def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                        curl ${pd_url} | tar xz
                        """
                    }
                }
                dir("tidb") {
                    deleteDir()
                    def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                    def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                    def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"

                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                        curl ${tidb_url} | tar xz
                        """
                    }
                }
            }
            
            dir("tikv") { deleteDir() }

            unstash "tikv"
            sh "find tikv"
        }

        stage('Integration Push Down Test') {
            def pd_bin = "${ws}/pd/bin/pd-server"
            def tikv_bin = "${ws}/tikv/bin/tikv-server"
            def tidb_src_dir = "${ws}/tidb"
            dir('copr-test') {
                container('golang') {
                    try {
                        sh """
                        pd_bin=${pd_bin} tikv_bin=${tikv_bin} tidb_src_dir=${tidb_src_dir} make push-down-test
                        """
                    } catch (Exception e) {
                        def build_dir = "push-down-test/build"
                        sh "cat ${build_dir}/tidb_no_push_down.log || true"
                        sh "cat ${build_dir}/tidb_with_push_down.log || true"
                        sh "cat ${build_dir}/pd_with_push_down.log || true"
                        sh "cat ${build_dir}/tikv_with_push_down.log || true"
        
                        sh "echo Test failed. Check out logs above."
                        
                        throw e;
                    }
                }
            }
        }

    }
    currentBuild.result = "SUCCESS"
} 

catch (Exception e) {
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

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        slackSend channel: '#push-down-expr-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
