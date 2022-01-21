

def get_sha(repo,branch) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
}

def test(ghprbActualCommit,ghprbCommentBody){
    println ghprbCommentBody
    build(job: "tispark_ghpr_integration_test",
            parameters: [
                    string(name: 'triggered_by_upstream_ci', value: "daily-trigger-tispark"),
                    booleanParam(name: 'daily_test', value: true),
                    string(name: 'ghprbActualCommit', value: ghprbActualCommit),
                    string(name: 'ghprbCommentBody', value: ghprbCommentBody),
            ],
            wait: true, propagate: true)
}

node("lightweight_pod") {
    container("golang"){


        stage("Debug INFO") {
            commitID = get_sha("tispark","master")
            println "tispark master latest commit id: ${commitID}"

            commitID2 = get_sha("tispark","release-2.4")
            println "tispark master latest commit2 id: ${commitID2}"
        }

        stage("Trigger job") {
            parallel(
                    test1: {
                        test(commitID,"tidb=v5.3.0 pd=v5.3.0 tiflash=v5.3.0 tikv=v5.3.0 test-spark-catalog=true")
                    },

                    test2: {
                        test(commitID,"tidb=v5.3.0 pd=v5.3.0 tiflash=v5.3.0 tikv=v5.3.0 test-spark-catalog=false")
                    },

                    test3: {
                        test(commitID,"tidb=v5.3.0 pd=v5.3.0 tiflash=v5.3.0 tikv=v5.3.0 profile=spark-3.1.1 test-spark-catalog=true")
                    },

                    test4: {
                        test(commitID,"tidb=v5.3.0 pd=v5.3.0 tiflash=v5.3.0 tikv=v5.3.0 profile=spark-3.1.1 test-spark-catalog=false")
                    },
            )

            parallel(
                    test5: {
                        test(commitID,"tidb=v5.2.3  pd=v5.2.3 tiflash=v5.2.3 tikv=v5.2.3 test-spark-catalog=true")
                    },

                    test6: {
                        test(commitID,"tidb=v5.2.3  pd=v5.2.3  tiflash=v5.2.3 tikv=v5.2.3 test-spark-catalog=false")
                    },

                    test7: {
                        test(commitID,"tidb=v5.2.3  pd=v5.2.3  tiflash=v5.2.3 tikv=v5.2.3 profile=spark-3.1.1 test-spark-catalog=true")
                    },

                    test8: {
                        test(commitID,"tidb=v5.2.3  pd=v5.2.3 tiflash=v5.2.3 tikv=v5.2.3 profile=spark-3.1.1 test-spark-catalog=false")
                    },
            )

            parallel(
                    test9: {
                        test(commitID,"tidb=v5.1.3  pd=v5.1.3 tiflash=v5.1.3 tikv=v5.1.3 test-spark-catalog=true")
                    },

                    test10: {
                        test(commitID,"tidb=v5.1.3  pd=v5.1.3  tiflash=v5.1.3 tikv=v5.1.3 test-spark-catalog=false")
                    },

                    test11: {
                        test(commitID,"tidb=v5.1.3  pd=v5.1.3  tiflash=v5.1.3 tikv=v5.1.3 profile=spark-3.1.1 test-spark-catalog=true")
                    },

                    test12: {
                        test(commitID,"tidb=v5.1.3  pd=v5.1.3 tiflash=v5.1.3 tikv=v5.1.3 profile=spark-3.1.1 test-spark-catalog=false")
                    },
            )

            parallel(
                    test13: {
                        test(commitID,"tidb=v5.0.6  pd=v5.0.6 tiflash=v5.0.6 tikv=v5.0.6 test-spark-catalog=true")
                    },

                    test14: {
                        test(commitID,"tidb=v5.0.6  pd=v5.0.6  tiflash=v5.0.6 tikv=v5.0.6 test-spark-catalog=false")
                    },

                    test15: {
                        test(commitID,"tidb=v5.0.6  pd=v5.0.6  tiflash=v5.0.6 tikv=v5.0.6 profile=spark-3.1.1 test-spark-catalog=true")
                    },

                    test16: {
                        test(commitID,"tidb=v5.0.6  pd=v5.0.6 tiflash=v5.0.6 tikv=v5.0.6 profile=spark-3.1.1 test-spark-catalog=false")
                    },
            )

            parallel(
                    test17: {
                        test(commitID,"tidb=v4.0.16  pd=v4.0.16 tiflash=v4.0.16 tikv=v4.0.16 test-spark-catalog=true")
                    },

                    test18: {
                        test(commitID,"tidb=v4.0.16  pd=v4.0.16  tiflash=v4.0.16 tikv=v4.0.16 test-spark-catalog=false")
                    },

                    test19: {
                        test(commitID,"tidb=v4.0.16  pd=v4.0.16  tiflash=v4.0.16 tikv=v4.0.16 profile=spark-3.1.1 test-spark-catalog=true")
                    },

                    test20: {
                        test(commitID,"tidb=v4.0.16  pd=v4.0.16 tiflash=v4.0.16 tikv=v4.0.16 profile=spark-3.1.1 test-spark-catalog=false")
                    },
            )

            parallel(
                    test17: {
                        test(commitID2,"tidb=v4.0.16  pd=v4.0.16 tiflash=v4.0.16 tikv=v4.0.16 test-spark-catalog=true")
                    },

                    test18: {
                        test(commitID2,"tidb=v4.0.16  pd=v4.0.16  tiflash=v4.0.16 tikv=v4.0.16 test-spark-catalog=false")
                    },

                    test19: {
                        test(commitID2,"tidb=v4.0.16  pd=v4.0.16  tiflash=v4.0.16 tikv=v4.0.16 profile=spark-3.1.1 test-spark-catalog=true")
                    },

                    test20: {
                        test(commitID2,"tidb=v4.0.16  pd=v4.0.16 tiflash=v4.0.16 tikv=v4.0.16 profile=spark-3.1.1 test-spark-catalog=false")
                    },
            )

            parallel(
                    test21: {
                        test(commitID2,"tidb=v5.3.0 pd=v5.3.0 tiflash=v5.3.0 tikv=v5.3.0 profile=scala-2.11 profile=spark-2.3")
                    },

                    test22: {
                        test(commitID2,"tidb=v5.3.0 pd=v5.3.0 tiflash=v5.3.0 tikv=v5.3.0 profile=scala-2.11")
                    },

                    test23: {
                        test(commitID2,"tidb=v5.3.0 pd=v5.3.0 tiflash=v5.3.0 tikv=v5.3.0 profile=scala-2.12")
                    },
            )

            parallel(
                    test24: {
                        test(commitID2,"tidb=v5.2.3 pd=v5.2.3 tiflash=v5.2.3 tikv=v5.2.3 profile=scala-2.11 profile=spark-2.3")
                    },

                    test25: {
                        test(commitID2,"tidb=v5.2.3 pd=v5.2.3 tiflash=v5.2.3 tikv=v5.2.3 profile=scala-2.11")
                    },

                    test26: {
                        test(commitID2,"tidb=v5.2.3 pd=v5.2.3 tiflash=v5.2.3 tikv=v5.2.3 profile=scala-2.12")
                    },
            )

            parallel(
                    test27: {
                        test(commitID2,"tidb=v5.1.3 pd=v5.1.3 tiflash=v5.1.3 tikv=v5.1.3 profile=scala-2.11 profile=spark-2.3")
                    },

                    test28: {
                        test(commitID2,"tidb=v5.1.3 pd=v5.1.3 tiflash=v5.1.3 tikv=v5.1.3 profile=scala-2.11")
                    },

                    test29: {
                        test(commitID2,"tidb=v5.1.3 pd=v5.1.3 tiflash=v5.1.3 tikv=v5.1.3 profile=scala-2.12")
                    },
            )

            parallel(
                    test30: {
                        test(commitID2,"tidb=v5.0.6 pd=v5.0.6 tiflash=v5.0.6 tikv=v5.0.6 profile=scala-2.11 profile=spark-2.3")
                    },

                    test31: {
                        test(commitID2,"tidb=v5.0.6 pd=v5.0.6 tiflash=v5.1.3 tikv=v5.0.6 profile=scala-2.11")
                    },

                    test32: {
                        test(commitID2,"tidb=v5.0.6 pd=v5.0.6 tiflash=v5.0.6 tikv=v5.0.6 profile=scala-2.12")
                    },
            )

            parallel(
                    test33: {
                        test(commitID2,"tidb=v4.0.16 pd=v4.0.16 tiflash=v4.0.16 tikv=v4.0.16 profile=scala-2.11 profile=spark-2.3")
                    },

                    test34: {
                        test(commitID2,"tidb=v4.0.16 pd=v4.0.16 tiflash=v4.0.16 tikv=v4.0.16 profile=scala-2.11")
                    },

                    test35: {
                        test(commitID2,"tidb=v4.0.16 pd=v4.0.16 tiflash=v4.0.16 tikv=v4.0.16 profile=scala-2.12")
                    },
            )

        }

    }
}