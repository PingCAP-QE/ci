def slackcolor = 'good'
def githash


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

def specStr = "+refs/heads/*:refs/remotes/origin/*"
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

podYAML = '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    ci-engine: ci.pingcap.net
'''

POD_NAMESPACE = "jenkins-tidb-mergeci"

def run_with_pod(Closure body) {
    def label = POD_LABEL
    podTemplate(label: label,
            cloud: "kubernetes-ksyun",
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            yaml: podYAML,
            yamlMergeStrategy: merge(),
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
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}


try {
    run_with_pod {
        def ws = pwd()

        stage("debuf info"){
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            println "work space path:\n${ws}"
        }

        stage("Checkout") {
            container("golang") {
                sh "whoami && go version"
                dir("${ws}/go/src/github.com/pingcap/tidb") {
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 10]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                    } catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            // if checkout one pr failed, we fallback to fetch all thre pr data
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 10]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                        }
                    }
                    sh "git checkout -f ${ghprbActualCommit}"
                }
            }
        }

        stage("Build tidb-server and e2e"){
            def builds = [:]
            builds["Test"] = {
                stage("Build"){
                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(20) {
                                try {
                                    sh """
                                    cd tests/graceshutdown
                                    make
                                    sh run-tests.sh
                                    """
                                } catch (err) {
                                    sh "cat /tmp/tidb_gracefulshutdown/tidb5501.log"
                                    throw err
                                }

                                try {
                                    sh """

                                    cd tests/globalkilltest
                                    tikv_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tikv/master/sha1"`
                                    tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"

                                    pd_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/pd/master/sha1"`
                                    pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/\${pd_sha1}/centos7/pd-server.tar.gz"


                                    while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 10; done
                                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  \${tikv_url}
                                    tar -xvz bin/ -f tikv-server.tar.gz && rm -rf tikv-server.tar.gz

                                    while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 10; done
                                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  \${pd_url}
                                    tar -xvz bin/ -f pd-server.tar.gz && rm -rf pd-server.tar.gz
                                    ls -lhrt ./bin
                                    make
                                    ls -lhrt ./bin
                                    PD=./bin/pd-server  TIKV=./bin/tikv-server sh run-tests.sh
                                    """
                                } catch (err) {
                                    sh """
                                    cat /tmp/tidb_globalkilltest/tidb5001.log
                                    cat /tmp/tidb_globalkilltest/tidb5002.log
                                    """
                                    throw err
                                }
                            }
                        }
                    }
                }
            }

            parallel builds
        }

    }
    currentBuild.result = "SUCCESS"
}catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/e2e-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
