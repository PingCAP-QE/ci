
// example commit msg
// expression: fix wrong result type for greatest/least (#29408) (#29912)
// close #29019
@NonCPS
def extract_pull_id(MSG){
    def resp = []
    def m1 = MSG =~ /(\#\b\d+)/
    if (m1) {
        for (int i = 0; i < m1.count; i++ ) {
            println m1[i][0]
            resp.add(m1[i][0])
        }
    }
    m1 = null

    return resp
}

@NonCPS // has to be NonCPS or the build breaks on the call to .each
def parseBuildResult(list) {
    def total_test = 0
    def failed_test = 0
    def success_test = 0

    list.each { item ->
        echo "${item}"
        if (item.status == "success") {
            success_test += 1
        } else {
            failed_test += 1
        }
    }
    total_test = success_test + failed_test
    def resp_str = ""
    if (failed_test > 0) {
        resp_str = "failed ${failed_test}, success ${success_test}, total ${total_test}"
    } else {
        resp_str = "all ${total_test} tests passed"
    }

    return resp_str
}


def taskStartTimeInMillis = System.currentTimeMillis()

label = "${JOB_NAME}-${BUILD_NUMBER}"
def run_with_pod(Closure body) {
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tidb-mergeci"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
    def jnlp_docker_image = "jenkins/inbound-agent:3148.v532a_7e715ee3-10"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '1000m', resourceRequestMemory: '2Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],

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

// TODO
// TIDB_COMMIT_ID = "d3a02f416aaa1eda89ce6bec2242ea8254ccfa46"
// TIDB_BRANCH = "master"

run_with_pod {
    stage("Print env"){
        // commit id / branch / pusher / commit message
        def trimPrefix = {
            it.startsWith('refs/heads/') ? it - 'refs/heads/' : it
        }

        if ( env.REF != '' ) {
            echo 'trigger by remote invoke'
            println "${ref}"
            TIDB_BRANCH = trimPrefix(ref)
            // def m1 = GEWT_COMMIT_MSG =~ /(?<=\()(.*?)(?=\))/
            // if (m1) {
            //     GEWT_PULL_ID = "${m1[0][1]}"
            //     GEWT_PULL_ID = GEWT_PULL_ID.substring(1)
            // }
            // m1 = null
            if (TIDB_BRANCH == "master") {
                GEWT_PULL_ID = extract_pull_id(GEWT_COMMIT_MSG)[0].replaceAll("#", "")
            } else {
                GEWT_PULL_ID = extract_pull_id(GEWT_COMMIT_MSG)[1].replaceAll("#", "")
            }

            echo "commit_msg=${GEWT_COMMIT_MSG}"
            echo "author=${GEWT_AUTHOR}"
            echo "author_email=${GEWT_AUTHOR_EMAIL}"
            echo "pull_id=${GEWT_PULL_ID}"
        } else {
            echo 'trigger manually'
            echo "param ref not exist"
        }

        if ( params.TIDB_COMMIT_ID == '') {
            echo "invalid param TIDB_COMMIT_ID"
            currentBuild.result = "FAILURE"
            error('Stopping early… invalid param TIDB_COMMIT_ID')
        } else {
            println "param TIDB_COMMIT_ID: ${params.TIDB_COMMIT_ID}"
        }

        echo "COMMIT=${TIDB_COMMIT_ID}"
        echo "BRANCH=${TIDB_BRANCH}"

        default_params = [
                string(name: 'triggered_by_upstream_ci', value: "tidb_integration_test_ci_shadow_debug"),
                booleanParam(name: 'release_test', value: true),
                booleanParam(name: 'update_commit_status', value: true),
                string(name: 'release_test__release_branch', value: TIDB_BRANCH),
                string(name: 'release_test__tidb_commit', value: TIDB_COMMIT_ID),
        ]

        echo("default params: ${default_params}")

    }

    def pipeline_result = []
    def triggered_job_result = []

    try {
        stage("Build") {
            build(job: "tidb_merged_pr_build", parameters: default_params, wait: true, propagate: true)
        }
        stage("Trigger Test Job") {
            container("golang") {
                parallel(
                        // integration test
                        tidb_ghpr_integration_br_test: {
                            def result = build(job: "tidb_ghpr_integration_br_test", parameters: default_params, wait: true, propagate: false)
                            triggered_job_result << ["name": "tidb_ghpr_integration_br_test", "type": "tidb-merge-ci-checker" , "result": result]
                            if (result.getResult() != "SUCCESS") {
                                throw new Exception("tidb_ghpr_integration_br_test failed")
                            }
                        },

                        // TODO: enable this job when copr-test fixed (currently unstable)
                        // ichn-hu is working on it
                        tidb_ghpr_integration_copr_test: {
                            def result = build(job: "tidb_ghpr_integration_copr_test", parameters: default_params, wait: true, propagate: false)
                            triggered_job_result << ["name": "tidb_ghpr_integration_copr_test", "type": "tidb-merge-ci-checker" , "result": result]
                            if (result.getResult() != "SUCCESS") {
                                throw new Exception("tidb_ghpr_integration_copr_test failed")
                            }
                        },

                        // TODO : enable this job when tidb unit test more stable
                        // bb7133 is working on it
                        // coverage
                        // tidb_ghpr_coverage: {
                        //     def result = build(job: "tidb_ghpr_coverage", parameters: default_params, wait: true, propagate: false)
                        //     triggered_job_result << ["name": "tidb_ghpr_coverage", "type": "tidb-merge-ci-checker" , "result": result]
                        //     if (result.getResult() != "SUCCESS") {
                        //         throw new Exception("tidb_ghpr_coverage failed")
                        //     }
                        // },

                )
            }
        }

        currentBuild.result = "SUCCESS"
    } catch(Exception e) {
        currentBuild.result = "FAILURE"
        println "catch_exception Exception"
        println e
    } finally {
        container("golang") {
            stage("summary") {
                for (result_map in triggered_job_result) {
                    def name = result_map["name"]
                    def type = result_map["type"]
                    def triggered_job_summary = ""
                    if (result_map.result.getDescription() != null && result_map.result.getDescription() != "") {
                        if (name == "tidb_ghpr_coverage") {
                            println "this is tidb_ghpr_coverage"
                            triggered_job_summary = result_map.result.getDescription()
                        } else {
                            // println "description: ${result_map.result.getDescription()}"
                            def jsonObj = readJSON text: result_map.result.getDescription()
                            triggered_job_summary = parseBuildResult(jsonObj)
                            writeJSON file: "${name}.json", json: result_map.result.getDescription(), pretty: 4
                        }
                    }
                }
            }
        }
    }
}
