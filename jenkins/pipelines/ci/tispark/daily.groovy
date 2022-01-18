

def get_sha(repo,branch) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
}

node("lightweight_pod") {
    container("golang"){


        stage("Debug INFO") {
            commitID = get_sha("tispark","master")
            println "tispark master latest commit id: ${commitID}"
        }

        stage("Trigger job") {
            parallel(
                    test1: {
                        println "tidb=master pd=master tiflash=master tikv=master test-spark-catalog=true (spark:3.0.2)"
                        build(job: "tispark_ghpr_integration_test",
                                parameters: [
                                        string(name: 'triggered_by_upstream_ci', value: "daily-trigger-tispark"),
                                        booleanParam(name: 'daily_test', value: true),
                                        string(name: 'ghprbActualCommit', value: commitID),
                                        string(name: 'ghprbCommentBody', value: "tidb=master pd=master tiflash=master tikv=master test-spark-catalog=true"),
                                ],
                                wait: true, propagate: true)

                    },

                    test2: {
                        println "tidb=master pd=master tiflash=master tikv=master test-spark-catalog=false (spark:3.0.2)"
                        build(job: "tispark_ghpr_integration_test",
                                parameters: [
                                        string(name: 'triggered_by_upstream_ci', value: "daily-trigger-tispark"),
                                        booleanParam(name: 'daily_test', value: true),
                                        string(name: 'ghprbActualCommit', value: commitID),
                                        string(name: 'ghprbCommentBody', value: "tidb=master pd=master tiflash=master tikv=master test-spark-catalog=false"),
                                ],
                                wait: true, propagate: true)
                    },

                    test3: {
                        println "tidb=master pd=master tiflash=master tikv=master profile=spark-3.1.1  test-spark-catalog=true"
                        build(job: "tispark_ghpr_integration_test",
                                parameters: [
                                        string(name: 'triggered_by_upstream_ci', value: "daily-trigger-tispark"),
                                        booleanParam(name: 'daily_test', value: true),
                                        string(name: 'ghprbActualCommit', value: commitID),
                                        string(name: 'ghprbCommentBody', value: "tidb=master pd=master tiflash=master tikv=master profile=spark-3.1.1 test-spark-catalog=true"),
                                ],
                                wait: true, propagate: true)
                    },

                    test4: {
                        println "tidb=master pd=master tiflash=master tikv=master profile=spark-3.1.1  test-spark-catalog=false"
                        build(job: "tispark_ghpr_integration_test",
                                parameters: [
                                        string(name: 'triggered_by_upstream_ci', value: "daily-trigger-tispark"),
                                        booleanParam(name: 'daily_test', value: true),
                                        string(name: 'ghprbActualCommit', value: commitID),
                                        string(name: 'ghprbCommentBody', value: "tidb=master pd=master tiflash=master tikv=master profile=spark-3.1.1 test-spark-catalog=false"),
                                ],
                                wait: true, propagate: true)
                    },
            )

        }

    }
}