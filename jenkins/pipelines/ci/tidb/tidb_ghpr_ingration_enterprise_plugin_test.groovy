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

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
ENTERPRISE_PLUGIN_BRANCH = ghprbTargetBranch
ENTERPRISE_PLUGIN_REF_SPEC = "+refs/heads/*:refs/remotes/origin/*"
POD_NAMESPACE = "jenkins-tidb-mergeci"
POD_GO_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.19:latest"

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    podTemplate(label: label,
            cloud: cloud,namespace: POD_NAMESPACE,idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                containerTemplate(
                    name: 'golang', alwaysPullImage: false,image: "${POD_GO_IMAGE}", ttyEnabled: true,
                    resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',command: '/bin/sh -c', args: 'cat',
                    envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                )
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            body()
        }
    }
}

try {
    run_with_pod {
        stage('Check code') {
            ws = pwd()
            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(15) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 2; done
                        curl ${tidb_url} | tar xz -C ./
                        pwd && ls -alh bin/
                        ./bin/tidb-server -V
                        """
                    }
                }
                dir("go/src/github.com/pingcap-inc/enterprise-plugin") {
                    timeout(15) {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: ENTERPRISE_PLUGIN_BRANCH]],
                            doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'],
                                [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]],
                            submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: ENTERPRISE_PLUGIN_REF_SPEC,
                            url: 'git@github.com:pingcap-inc/enterprise-plugin.git']]
                        ]
                        // ENTERPRISE_PLUGIN_REF_SPEC = "+refs/pull/80/head:refs/remotes/origin/PR-80"
                        // checkout([$class: 'GitSCM', branches: [[name: "FETCH_HEAD"]],
                        //     extensions: [[$class: 'LocalBranch']],
                        //     userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: ENTERPRISE_PLUGIN_REF_SPEC, url: 'git@github.com:pingcap-inc/enterprise-plugin.git']]])
                        plugin_githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                        println "plugin_githash: ${plugin_githash}"
                    }
                }
            }
        }
        stage("ENTERPRISE_PLUGIN TEST") {
            container("golang") {
                dir("go/src/github.com/pingcap-inc/enterprise-plugin") {
                    timeout(15) {
                        sh """
                        cd test/
                        export PD_BRANCH=${ghprbTargetBranch}
                        export TIKV_BRANCH=${ghprbTargetBranch}
                        export TIDB_REPO_PATH=${ws}/go/src/github.com/pingcap/tidb
                        export PLUGIN_REPO_PATH=${ws}/go/src/github.com/pingcap-inc/enterprise-plugin
                        ./test.sh
                        """
                    }
                }
            }
        }
    }
    currentBuild.result = "SUCCESS"
}
catch (Exception e) {
    currentBuild.result = "FAILURE"
    echo "${e}"
}
finally {

}
