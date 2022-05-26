def notRun = 1

def slackcolor = 'good'
def githash

def specStr = "+refs/pull/*:refs/remotes/origin/pr/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tidb_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
    specStr = "+refs/heads/*:refs/remotes/origin/*"
}

def TIKV_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch

if (params.containsKey("release_test") && params.triggered_by_upstream_ci == null) {
    TIKV_BRANCH = params.release_test__tikv_commit
    PD_BRANCH = params.release_test__pd_commit
}

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

GO_VERSION = "go1.18"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
]

node("master") {
    deleteDir()
    def ws = pwd()
    sh "curl -O https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib.groovy"
    def script_path = "${ws}/goversion-select-lib.groovy"
    def goversion_lib = load script_path
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-tidb"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            nodeSelector: 'role_type=slave',
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${POD_GO_IMAGE}", ttyEnabled: true,
                            privileged: true,
                            resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                            resourceLimitCpu: '30000m', resourceLimitMemory: "100Gi",
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: false,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes: [
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            println "go image: ${POD_GO_IMAGE}"
            body()
        }
    }
}

def upload_test_result(reportDir) {
    if (!fileExists(reportDir)){
        return
    }
    try {
        id=UUID.randomUUID().toString()
        def filepath = "tipipeline/test/report/${JOB_NAME}/${BUILD_NUMBER}/${id}/report.xml"
        sh """
        curl -F ${filepath}=@${reportDir} ${FILE_SERVER_URL}/upload
        """
        def downloadPath = "${FILE_SERVER_URL}/download/${filepath}"
        def all_results = [
            jenkins_job_name: "${JOB_NAME}",
            jenkins_url: "${env.RUN_DISPLAY_URL}",
            repo: "${ghprbGhRepository}",
            commit_id: ghprbActualCommit,
            branch: ghprbTargetBranch,
            junit_report_url: downloadPath,
            pull_request: ghprbPullId.toInteger(),
            author: ghprbPullAuthorLogin
        ]
        def json = groovy.json.JsonOutput.toJson(all_results)
        response = httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: json, url: "http://172.16.5.14:30792/report/", validResponseCodes: '200'
    }catch (Exception e) {
        // upload test case result to tipipeline, do not block ci
        print "upload test result to tipipeline failed, continue."
    }
}

try {
    run_with_pod {
        stage("Pre-check"){
            if (!params.force){
                container("golang"){
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

        def ws = pwd()

        stage("Checkout") {
            // update code
            dir("/home/jenkins/agent/code-archive") {
                // delete to clean workspace in case of agent pod reused lead to conflict.
                deleteDir()
                // copy code from nfs cache
                container("golang") {
                    if(fileExists("/home/jenkins/agent/ci-cached-code-daily/src-tidb.tar.gz")){
                        timeout(5) {
                            sh """
                                cp -R /home/jenkins/agent/ci-cached-code-daily/src-tidb.tar.gz*  ./
                                mkdir -p ${ws}/go/src/github.com/pingcap/tidb
                                tar -xzf src-tidb.tar.gz -C ${ws}/go/src/github.com/pingcap/tidb --strip-components=1
                            """
                        }
                    }
                    dir("${ws}/go/src/github.com/pingcap/tidb") {
                        if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/tidb"
                            echo "Clean dir then get tidb src code from fileserver"
                            deleteDir()
                        }
                        if(!fileExists("${ws}/go/src/github.com/pingcap/tidb/Makefile")) {
                            dir("${ws}/go/src/github.com/pingcap/tidb") {
                                sh """
                                    rm -rf /home/jenkins/agent/code-archive/tidb.tar.gz
                                    rm -rf /home/jenkins/agent/code-archive/tidb
                                    wget -O /home/jenkins/agent/code-archive/tidb.tar.gz  ${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb.tar.gz -q --show-progress
                                    tar -xzf /home/jenkins/agent/code-archive/tidb.tar.gz -C ./ --strip-components=1
                                """
                            }
                        }
                        try {
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                        }   catch (info) {
                                retry(2) {
                                    echo "checkout failed, retry.."
                                    sleep 5
                                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                        deleteDir()
                                    }
                                    // if checkout one pr failed, we fallback to fetch all thre pr data
                                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                                }
                            }
                        sh "git checkout -f ${ghprbActualCommit}"
                        sh """
                        GO111MODULE=on go build -race -o bin/explain_test_tidb-server github.com/pingcap/tidb/tidb-server
                        """
                    }
                }
            }
            stash includes: "go/src/github.com/pingcap/tidb/**", name: "tidb"
        }

        def tests = [:]
        tests["Build & Test"] = {
            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(15) {
                        sh """
                        package_base=`grep module go.mod | head -n 1 | awk '{print \$2}'`
                        sed -i  's,go list ./...| grep -vE "cmd",go list ./...| grep -vE "cmd" | grep -vE "store/tikv\$\$",' ./Makefile
                        
                        if [ \"${ghprbTargetBranch}\" == \"master\" ]  ;then EXTRA_TEST_ARGS='-timeout 9m'  make test_part_parser && make gogenerate ; fi > test.log || \\
                        (cat test.log; cat test.log |grep -Ev "^\\[[[:digit:]]{4}(/[[:digit:]]{2}){2}" | grep -A 30 "\\-------" | grep -A 29 "FAIL"; false)
                        # if grep -q gogenerate "Makefile";then  make gogenerate ; fi
                        """
                    }
                }
            }
        }


        if (ghprbTargetBranch == "master"){
            tests["New Collation Enabled"] = {
                run_with_pod {
                    deleteDir()
                    unstash 'tidb'
                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") { 
                             timeout(15) { 
                                 try {
                                    ws = pwd()
                                    def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                                    def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                                    tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                                    def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                                    pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                                    sh """
                                    curl ${tikv_url} | tar xz
                                    curl ${pd_url} | tar xz bin

                                    # Disable pipelined pessimistic lock temporarily until tikv#11649 is resolved
                                    echo -e "[pessimistic-txn]\npipelined = false" > tikv.toml

                                    bin/pd-server -name=pd1 --data-dir=pd1 --client-urls=http://127.0.0.1:2379 --peer-urls=http://127.0.0.1:2378 -force-new-cluster &> pd1.log &
                                    bin/tikv-server --pd=127.0.0.1:2379 -s tikv1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 --advertise-status-addr=127.0.0.1:20165 -C tikv.toml -f  tikv1.log &
                        
                                    bin/pd-server -name=pd2 --data-dir=pd2 --client-urls=http://127.0.0.1:2389 --peer-urls=http://127.0.0.1:2388 -force-new-cluster &>  pd2.log &
                                    bin/tikv-server --pd=127.0.0.1:2389 -s tikv2 --addr=0.0.0.0:20170 --advertise-addr=127.0.0.1:20170 --advertise-status-addr=127.0.0.1:20175 -C tikv.toml -f  tikv2.log &
                    
                                    bin/pd-server -name=pd3 --data-dir=pd3 --client-urls=http://127.0.0.1:2399 --peer-urls=http://127.0.0.1:2398 -force-new-cluster &> pd3.log &
                                    bin/tikv-server --pd=127.0.0.1:2399 -s tikv3 --addr=0.0.0.0:20190 --advertise-addr=127.0.0.1:20190 --advertise-status-addr=127.0.0.1:20185 -C tikv.toml -f  tikv3.log &

                                    # GO111MODULE=on go build -race -o bin/explain_test_tidb-server github.com/pingcap/tidb/tidb-server
                                    ls -alh ./bin/

                                    export TIDB_SERVER_PATH=${ws}/bin/explain_test_tidb-server
                                    export TIKV_PATH=127.0.0.1:2379
                                    chmod +x cmd/explaintest/run-tests.sh
                                    cd cmd/explaintest && ls -alh
                                    ./run-tests.sh -d y
                                    """
                                 } catch (Exception e){ 
                                    sh "cat ${ws}/pd1.log || true"
                                    sh "cat ${ws}/tikv1.log || true"
                                    sh "cat ${ws}/pd2.log || true"
                                    sh "cat ${ws}/tikv2.log || true"
                                    sh "cat ${ws}/pd3.log || true"
                                    sh "cat ${ws}/tikv3.log || true"
                                    sh "cat ${ws}/cmd/explaintest/explain-test.out || true"
                                    throw e
                                 } finally {
                                    sh """
                                    set +e
                                    killall -9 -r -q tikv-server
                                    killall -9 -r -q pd-server
                                    set -e
                                    """
                                 }
                             }
                        }
                    }
                }
            }

            tests["New Collation Disabled"] = {
                run_with_pod {
                    deleteDir()
                    unstash 'tidb'
                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") { 
                             timeout(15) { 
                                 try {
                                    ws = pwd()
                                    def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                                    def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                                    tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                                    def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                                    pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                                    sh """
                                    curl ${tikv_url} | tar xz
                                    curl ${pd_url} | tar xz bin

                                    # Disable pipelined pessimistic lock temporarily until tikv#11649 is resolved
                                    echo -e "[pessimistic-txn]\npipelined = false" > tikv.toml

                                    bin/pd-server -name=pd1 --data-dir=pd1 --client-urls=http://127.0.0.1:2379 --peer-urls=http://127.0.0.1:2378 -force-new-cluster &> pd1.log &
                                    bin/tikv-server --pd=127.0.0.1:2379 -s tikv1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 --advertise-status-addr=127.0.0.1:20165 -C tikv.toml -f  tikv1.log &
                        
                                    bin/pd-server -name=pd2 --data-dir=pd2 --client-urls=http://127.0.0.1:2389 --peer-urls=http://127.0.0.1:2388 -force-new-cluster &>  pd2.log &
                                    bin/tikv-server --pd=127.0.0.1:2389 -s tikv2 --addr=0.0.0.0:20170 --advertise-addr=127.0.0.1:20170 --advertise-status-addr=127.0.0.1:20175 -C tikv.toml -f  tikv2.log &
                    
                                    bin/pd-server -name=pd3 --data-dir=pd3 --client-urls=http://127.0.0.1:2399 --peer-urls=http://127.0.0.1:2398 -force-new-cluster &> pd3.log &
                                    bin/tikv-server --pd=127.0.0.1:2399 -s tikv3 --addr=0.0.0.0:20190 --advertise-addr=127.0.0.1:20190 --advertise-status-addr=127.0.0.1:20185 -C tikv.toml -f  tikv3.log &

                                    # GO111MODULE=on go build -race -o bin/explain_test_tidb-server github.com/pingcap/tidb/tidb-server
                                    ls -alh ./bin/

                                    export TIDB_SERVER_PATH=${ws}/bin/explain_test_tidb-server
                                    export TIKV_PATH=127.0.0.1:2379
                                    chmod +x cmd/explaintest/run-tests.sh
                                    cd cmd/explaintest && ls -alh
                                    ./run-tests.sh -d n
                                    """
                                 } catch (Exception e){ 
                                    sh "cat ${ws}/pd1.log || true"
                                    sh "cat ${ws}/tikv1.log || true"
                                    sh "cat ${ws}/pd2.log || true"
                                    sh "cat ${ws}/tikv2.log || true"
                                    sh "cat ${ws}/pd3.log || true"
                                    sh "cat ${ws}/tikv3.log || true"
                                    sh "cat ${ws}/cmd/explaintest/explain-test.out || true"
                                    throw e
                                 } finally {
                                    sh """
                                    set +e
                                    killall -9 -r -q tikv-server
                                    killall -9 -r -q pd-server
                                    set -e
                                    """
                                 }
                             }
                        }
                    }
                }
            }
        }
        parallel tests

        currentBuild.result = "SUCCESS"
    
        container("golang"){
            sh """
                echo "done" > done
                curl -F ci_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
            """
        }
    }
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
                    string(name: 'TIDB_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/check_dev_2'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}

