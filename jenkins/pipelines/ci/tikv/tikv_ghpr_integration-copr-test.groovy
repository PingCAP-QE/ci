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

def TIDB_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch
def COPR_TEST_BRANCH = ghprbTargetBranch

if (ghprbPullTitle.find("Bump version") != null) {
    currentBuild.result = 'SUCCESS'
    return
}

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


GO_VERSION = "go1.21"
POD_GO_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.21:latest"
POD_LABEL = "${JOB_NAME}-${BUILD_NUMBER}-go121"

node("master") {
    deleteDir()
    def goversion_lib_url = 'https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib-v2.groovy'
    sh "curl --retry 3 --retry-delay 5 --retry-connrefused --fail -o goversion-select-lib.groovy  ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = goversion_lib.selectGoImage(ghprbTargetBranch)
    POD_LABEL = goversion_lib.getPodLabel(ghprbTargetBranch, JOB_NAME, BUILD_NUMBER)
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
    println "pod label: ${POD_LABEL}"
}

def run_with_pod(Closure body) {
    def label = POD_LABEL
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tikv"
    def jnlp_docker_image = "jenkins/inbound-agent:3148.v532a_7e715ee3-10"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "${POD_GO_IMAGE}", ttyEnabled: true,
                        resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]
                    )
            ]
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

try {
    stage('Build') {
        node("build") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            // checkout tikv
            // dir("/home/jenkins/agent/git/tikv") {
            //     if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
            //         deleteDir()
            //     }
            //     checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:tikv/tikv.git']]]
            // }

            def filepath = "builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
            def refspath = "refs/pingcap/tikv/pr/${ghprbPullId}/sha1"

            dir("tikv") {
                container("rust") {
                    deleteDir()
                    timeout(30) {
                        sh """
                        set +e
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        set -e
                        (curl ${tikv_url} | tar xz) || (sleep 15 && curl ${tikv_url} | tar xz)
                        """
                    }
                }
            }

            stash includes: "tikv/bin/**", name: "tikv"
            deleteDir()
        }
    }


    run_with_pod {
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
    println "currentBuild.result: ${currentBuild.result}"
}


if (params.containsKey("triggered_by_upstream_ci")) {
    stage("update commit status") {
        node("master") {
            if (currentBuild.result == "ABORTED") {
                PARAM_DESCRIPTION = 'Jenkins job aborted'
                // Commit state. Possible values are 'pending', 'success', 'error' or 'failure'
                PARAM_STATUS = 'error'
            } else if (currentBuild.result == "FAILURE") {
                PARAM_DESCRIPTION = 'Jenkins job failed'
                PARAM_STATUS = 'failure'
            } else if (currentBuild.result == "SUCCESS") {
                PARAM_DESCRIPTION = 'Jenkins job success'
                PARAM_STATUS = 'success'
            } else {
                PARAM_DESCRIPTION = 'Jenkins job meets something wrong'
                PARAM_STATUS = 'error'
            }
            def default_params = [
                    string(name: 'TIKV_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tikv/integration-copr-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tikv_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
