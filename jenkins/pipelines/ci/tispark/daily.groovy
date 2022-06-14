def get_sha(repo, branch) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
}

def test_base(commitName, ghprbActualCommit, ghprbCommentBody) {
    println commitName + " : " + ghprbCommentBody
    build(job: "tispark_ghpr_integration_test",
            parameters: [
                    string(name: 'triggered_by_upstream_ci', value: "daily-trigger-tispark"),
                    booleanParam(name: 'daily_test', value: true),
                    string(name: 'ghprbActualCommit', value: ghprbActualCommit),
                    string(name: 'ghprbCommentBody', value: ghprbCommentBody),
            ],
            wait: true, propagate: true)
}

def test_master_tiflash(version) {
    commitID = get_sha("tispark", "master")
    println "tispark master latest commit id: ${commitID}"
    parallel(
            test1: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version test-flash=true")
            }
    )
}

def test_master(version) {
    commitID = get_sha("tispark", "master")
    println "tispark master latest commit id: ${commitID}"
    parallel(
            test1: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
            },

            test2: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.1.1")
            },

            test3: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.2.1")
            },
    )
}

def test_release2_5(version) {
    commitID = get_sha("tispark", "release-2.5")
    println "tispark release-2.5 latest commit id: ${commitID}"
    parallel(
            test1: {
                test_base("release-2.5", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
            },


            test3: {
                test_base("release-2.5", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.1.1")
            },
    )
}

def test_release2_4(commitID, version) {
    commitID = get_sha("tispark", "release-2.4")
    println "tispark release-2.4 last commit id: ${commitID}"
    parallel(
            test5: {
                test_base("release-2.4", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=scala-2.11 profile=spark-2.3")
            },

            test6: {
                test_base("release-2.4", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=scala-2.11")
            },

            test7: {
                test_base("release-2.4", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=scala-2.12")
            },
    )
}

def test_release3_0(version) {
    commitID = get_sha("tispark", "release-3.0")
    println "tispark release-3.0 last commit id: ${commitID}"
    parallel(
            test1: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
            },

            test2: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.1.1")
            },

            test3: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.2.1")
            },
    )
}

// 大版本发版测试
def release_master(){
    commitID = get_sha("tispark", "master")
    version = "master"
    parallel(
            test1: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
            },

            test2: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.1.1")
            },

            test3: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.2.1")
            },
    )
   version = "release-6.0"
   parallel(
             test1: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
             },

             test2: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.1.1")
             },

             test3: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.2.1")
             },
     )

     version = "release-5.4"
     parallel(
             test1: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
             },

             test2: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.1.1")
             },

             test3: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.2.1")
             },
     )
     version = "release-5.3"
     parallel(
             test1: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
             },

              test2: {
                  test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.1.1")
              },

             test3: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.2.1")
             },
     )
     version = "release-5.2"
     parallel(
             test1: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
             },

              test2: {
                  test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.1.1")
              },

             test3: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.2.1")
             },
     )
     version = "release-5.1"
     parallel(
             test1: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
             },

              test2: {
                  test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.1.1")
              },

             test3: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.2.1")
             },
     )
     version = "release-5.0"
     parallel(

             test1: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
             },

              test2: {
                  test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.1.1")
              },

             test3: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.2.1")
             },
     )
     version = "release-4.0"
     parallel(

             test1: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
             },

             test2: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.1.1")
             },

             test3: {
                 test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark3.2.1")
             },
     )
     // tiflash test
     parallel(

             test1: {
                 test_base("master", commitID, "tidb=master pd=master tiflash=master tikv=master profile=spark3.2.1 test-flash=true")
             },

             test2: {
                 test_base("master", commitID, "tidb=v6.0.0 pd=v6.0.0 tiflash=v6.0.0 tikv=v6.0.0 profile=spark3.1.1 test-flash=true")
             },

             test3: {
                 test_base("master", commitID, "tidb=release-4.0 pd=release-4.0 tiflash=release-4.0 tikv=release-4.0 test-flash=true")
             },
     )

node("lightweight_pod") {
    container("golang") {

        stage("Trigger job") {
            def now = new Date().getDay()
            switch (now) {
                case 1:
                    test_master("release-4.0")
                    test_release2_4("release-4.0")
                    break
                case 2:
                    test_master(master, "release-5.0")
                    test_release2_5("release-5.0")
                    break
                case 3:
                    test_master(master, "release-5.4")
                    test_release3_0("release-5.4")
                    break
                case 4:
                    test_master(master, "release-6.0")
                    test_release2_4("release-6.0")
                    break
                case 5:
                    test_master(master, "master")
                    test_release2_5(release_2_5, "master")
                    break
                case 6:
                    test_release2_4("master")
                    test_release3_0("master")
                    break;
                case 7:
                    test_master_tiflash("master")
                    break;
            }
        }

    }
}
