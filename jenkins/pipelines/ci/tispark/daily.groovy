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

def test_master(commitID, version) {
    println "tispark master"
    parallel(
            test1: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version test-spark-catalog=true")
            },

            test2: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version test-spark-catalog=false")
            },

            test3: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.1.1 test-spark-catalog=true")
            },

            test4: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.1.1 test-spark-catalog=false")
            },
    )
}

def test_release2_4(commitID, version) {
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

node("lightweight_pod") {
    container("golang") {


        stage("Debug INFO") {
            master = get_sha("tispark", "master")
            println "tispark master latest commit id: ${master}"

            release_2_4 = get_sha("tispark", "release-2.4")
            println "tispark master release_2_4 commit id: ${release_2_4}"
        }

        stage("Trigger job") {
            def now = new Date().getDay()
            switch (now) {
                case 1:
                    test_master(master, "v5.3.0")
                    test_release2_4(release_2_4, "v5.3.0")
                    break
                case 2:
                    test_master(master, "v5.2.2")
                    test_release2_4(release_2_4, "v5.2.2")
                    break
                case 3:
                    test_master(master, "v5.1.2")
                    test_release2_4(release_2_4, "v5.1.2")
                    break
                case 4:
                    test_master(master, "v5.0.6")
                    test_release2_4(release_2_4, "v5.0.6")
                    break
                case 5:
                    test_master(master, "release-4.0")
                    test_release2_4(release_2_4, "release-4.0")
                    break
            }
        }

    }
}