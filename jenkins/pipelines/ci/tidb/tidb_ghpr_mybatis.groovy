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

def TIDB_TEST_BRANCH = "master"
def TIDB_PRIVATE_TEST_BRANCH = "master"

def MYBATIS3_URL = "${FILE_SERVER_URL}/download/static/mybatis-3-tidb.zip"

// parse tidb_test branch
def m3 = ghprbCommentBody =~ /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_TEST_BRANCH = "${m3[0][1]}"
}
// if (TIDB_TEST_BRANCH.startsWith("release-3")) {
// TIDB_TEST_BRANCH = "release-3.0"
// }
m3 = null
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

// parse tidb_private_test branch
def m4 = ghprbCommentBody =~ /tidb[_\-]private[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m4) {
    TIDB_PRIVATE_TEST_BRANCH = "${m4[0][1]}"
}
m4 = null

println "TIDB_PRIVATE_TEST_BRANCH=${TIDB_PRIVATE_TEST_BRANCH}"
all_task_result = []

POD_CLOUD = "kubernetes-ksyun"
POD_NAMESPACE = "jenkins-tidb"
podYAML = '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    ci-engine: ci.pingcap.net
'''

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def java_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_java:cached"
    podTemplate(label: label,
            cloud: POD_CLOUD,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            yaml: podYAML,
            yamlMergeStrategy: merge(),
            containers: [
                    containerTemplate(
                        name: 'java', alwaysPullImage: true,
                        image: "${java_image}", ttyEnabled: true,
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
    stage('Mybatis Test') {
        run_with_pod {
            try {
                container("java") {
                    def ws = pwd()
                    deleteDir()

                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    dir("go/src/github.com/pingcap/tidb") {
                        def url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                        def done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/done"
                        timeout(10) {
                            sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            rm -rf /tmp/tidb
                            set -e

                            while ! curl --output /dev/null --silent --head --fail ${done_url}; do sleep 1; done
                            curl ${url} | tar xz
                            rm -f bin/tidb-server
                            rm -f bin/tidb-server-race
                            cp bin/tidb-server-check bin/tidb-server
                            cat > config.toml << __EOF__
[performance]
join-concurrency = 1
__EOF__

                            bin/tidb-server -config config.toml > ${ws}/tidb_mybatis3_test.log 2>&1 &

                            """
                        }
                        if (!ghprbTargetBranch.startsWith("release-2")) {
                            retry(3) {
                                sh """
                                    sleep 5
                                    wget ${FILE_SERVER_URL}/download/mysql && chmod +x mysql
                                    mysql -h 127.0.0.1 -P4000 -uroot -e 'set @@global.tidb_enable_window_function = 0'
                                """
                            }
                        }
                    }

                    try {
                        dir("mybatis3") {
                            sh """
                            curl -L ${MYBATIS3_URL} -o travis-tidb.zip && unzip travis-tidb.zip && rm -rf travis-tidb.zip
                            cp -R mybatis-3-travis-tidb/. ./ && rm -rf mybatis-3-travis-tidb
                            """
                            retry(3) {
                                timeout(10) {
                                    sh """
                                    mvn -B clean test
                                    """
                                }
                            }
                        }
                    } catch (err) {
                        sh "cat ${ws}/tidb_mybatis3_test.log"
                        throw err
                    } finally {
                        sh "killall -9 -r tidb-server || true"
                    }
                }
                all_task_result << ["name": "Mybatis Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "Mybatis Test", "status": "failed", "error": err.message]
                throw err
            }
        }
    }

    currentBuild.result = "SUCCESS"
} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
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
} finally {
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/mybatis-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
