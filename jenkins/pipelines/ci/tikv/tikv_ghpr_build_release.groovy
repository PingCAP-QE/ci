

def release="make dist_release"
if ( ghprbTargetBranch == "master" || ghprbTargetBranch == "release-3.0" || ghprbTargetBranch == "release-3.1") {
    release = "make dist_release"
}

def ghprbCommentBody = params.ghprbCommentBody ?: null

def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

def m1 = (ghprbCommentBody =~ /\/release[\s|\\r|\\n]*\s([^\\].+)/)
if (m1) {
    release = "${m1[0][1]}"
}
m1 = null


println "release: $release"

try {
    node("build") {
        def ws = pwd()
        deleteDir()

        stage("Checkout") {
            // update cache
            container("rust") {
                dir("/home/jenkins/agent/git/tikv") {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "${specStr}", url: 'git@github.com:tikv/tikv.git']]]
                    sh """
                    git checkout -f ${ghprbActualCommit}
                    """
                }
            }
        }

        stage("Build") {
            dir("tikv") {
                container("rust") {
                    timeout(120) {
                        sh """
                        rm ~/.gitconfig || true
                        cp -R /home/jenkins/agent/git/tikv/. ./
                        git checkout -f ${ghprbActualCommit}
                        grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                        # if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                        #     echo using gcc 8
                        #     source /opt/rh/devtoolset-8/enable
                        # fi
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                        gcc --version
                        CARGO_TARGET_DIR=/home/jenkins/agent/.target ROCKSDB_SYS_STATIC=1 ${release}
                        if [[ "${release}" =~ "make dist_release" ]];then
	                        echo ${release}
                        else
	                        mkdir -p bin && cp /home/jenkins/agent/.target/release/tikv-server bin/
                        fi

                        if [[ "${release}" =~ "make titan_release" ]];then
	                        cp bin/tikv-server bin/tikv-server-titan
                        fi
                        """
                    }
                }
            }
        }

        stage("Upload") {
            def filepath = "builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
            def donepath = "builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/done"
            def refspath = "refs/pingcap/tikv/pr/${ghprbPullId}/sha1"

            dir("tikv") {
                container("rust") {
                    timeout(10) {
                        sh """
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

    node("master") {
        withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
            sh """
                rm -f comment-pr
                curl -O http://fileserver.pingcap.net/download/comment-pr
                chmod +x comment-pr
                ./comment-pr --token=$TOKEN --owner=tikv --repo=tikv --number=${ghprbPullId} --comment="download tikv at ${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
            """
        }
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    echo "${e}"
}
