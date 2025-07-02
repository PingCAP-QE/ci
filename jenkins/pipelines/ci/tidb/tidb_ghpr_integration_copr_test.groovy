def notRun = 1

echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tidb_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def TIDB_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch

def COPR_TEST_BRANCH = ghprbTargetBranch
def COPR_TEST_PR_ID = ""
def TIKV_BRANCH = ghprbTargetBranch

def refspecCoprTest = "+refs/heads/*:refs/remotes/origin/*"

println "coment body: ${ghprbCommentBody}"

// parse tikv branch
def m1 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIKV_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIKV_BRANCH=${TIKV_BRANCH}"

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

if (COPR_TEST_BRANCH.startsWith("pr/")) {
    COPR_TEST_PR_ID = COPR_TEST_BRANCH.substring(3)
    refspecCoprTest = "+refs/pull/${COPR_TEST_PR_ID}/head:refs/remotes/origin/PR-${COPR_TEST_PR_ID}"
    COPR_TEST_PR_ID = COPR_TEST_BRANCH.substring(3)
}
println "COPR_TEST_BRANCH=${COPR_TEST_BRANCH}"
println "COPR_TEST_PR_ID=${COPR_TEST_PR_ID}"

// def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
def release = "release"
all_task_result = []

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
    def namespace = "jenkins-tidb-mergeci"
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
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}


try {
    stage("Pre-check"){
        if (!params.force){
            node("master"){
                notRun = sh(returnStatus: true, script: """
                if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/ci_check/${JOB_NAME}/${ghprbActualCommit}; then exit 0; else exit 1; fi
                """)
            }
        }
        if (notRun == 0){
            println "the ${ghprbActualCommit} has been tested"
            throw new RuntimeException("hasBeenTested")
        }
    }

    run_with_pod {
        def ws = pwd()

        stage('Prepare') {
            dir("copr-test") {
                println "prepare copr-test"
                println "corp-test branch: ${COPR_TEST_BRANCH}"
                println "refspec: ${refspecCoprTest}"
                timeout(30) {
                    if (COPR_TEST_PR_ID != "") {
                        checkout([$class: 'GitSCM', branches: [[name: "FETCH_HEAD"]],
                        extensions: [[$class: 'LocalBranch']],
                        userRemoteConfigs: [[refspec: refspecCoprTest, url: 'https://github.com/tikv/copr-test.git']]])
                    } else {
                        checkout(changelog: false, poll: false, scm: [
                            $class: "GitSCM",
                            branches: [ [ name: COPR_TEST_BRANCH ] ],
                            userRemoteConfigs: [
                                    [
                                            url: 'https://github.com/tikv/copr-test.git',
                                            refspec: refspecCoprTest,
                                    ]
                            ],
                            extensions: [
                                    [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'],
                                    [$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true]
                            ],
                        ])
                    }
                }
            }
            container("golang") {
                dir("tikv"){
                    deleteDir()

                    timeout(30) {
                        def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                        sh"""
                        while ! curl --output /dev/null --silent --head --fail ${tikv_refs}; do sleep 5; done
                        """
                        def tikv_sha1 =  sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                        def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${tikv_url}
	                    tar -xvz bin/ -f tikv-server.tar.gz && rm -rf tikv-server.tar.gz
                        """
                    }
                }
                dir("pd") {
                    deleteDir()
                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                    def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                    def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                        wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${pd_url}
	                    tar -xvz bin/ -f pd-server.tar.gz && rm -rf pd-server.tar.gz
                        """
                    }
                }
                dir("tidb") {
                    deleteDir()
                    def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                    def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"

                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${tidb_url}
                        tar -xz -f tidb-server.tar.gz && rm -rf tidb-server.tar.gz
                        """
                    }
                }
            }
        }

        stage('Integration Push Down Test') {
            try {
                def pd_bin = "${ws}/pd/bin/pd-server"
                def tikv_bin = "${ws}/tikv/bin/tikv-server"
                def tidb_src_dir = "${ws}/tidb"
                dir('copr-test') {
                    container('golang') {
                        try{
                            timeout(30){
                                sh """
                                pd_bin=${pd_bin} tikv_bin=${tikv_bin} tidb_src_dir=${tidb_src_dir} make push-down-test
                                """
                            }
                        }catch (Exception e) {
                            def build_dir = "push-down-test/build"
                            sh "cat ${build_dir}/tidb_no_push_down.log || true"
                            sh "cat ${build_dir}/tidb_with_push_down.log || true"
                            sh "cat ${build_dir}/tikv_with_push_down.log || true"
                            sh "echo Test failed. Check out logs above."
                            throw e;
                        }

                    }
                }

                all_task_result << ["name": "Integration Push Down Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "Integration Push Down Test", "status": "failed", "error": err.message]
                throw err
            }
        }
    }
    currentBuild.result = "SUCCESS"
}
catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println e
    currentBuild.result = "ABORTED"
}
catch (Exception e) {
    if (e.getMessage().equals("hasBeenTested")) {
        currentBuild.result = "SUCCESS"
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
}
finally {
    stage("task summary") {
        if (all_task_result) {
            def json = groovy.json.JsonOutput.toJson(all_task_result)
            println "all_results: ${json}"
            currentBuild.description = "${json}"
        }
    }
}

if (params.containsKey("triggered_by_upstream_ci")  && params.get("triggered_by_upstream_ci") == "tidb_integration_test_ci") {
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
                    string(name: 'TIDB_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/integration-copr-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
