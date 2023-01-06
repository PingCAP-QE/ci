final K8S_NAMESPACE = "jenkins-tispark"
final GIT_FULL_REPO_NAME = 'pingcap/tispark'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tispark/latest/pod-release_master.yaml'

def get_sha(repo, branch) {
    sh "curl -C - --retry 3 -fs ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${branch} -s=${FILE_SERVER_URL}").trim()
}

def test_base(commitName, ghprbActualCommit, ghprbCommentBody) {
    println commitName + " : " + ghprbCommentBody
    build(job: "tispark_ghpr_integration_test",
            parameters: [
                    string(name: 'triggered_by_upstream_ci', value: "release_master"),
                    booleanParam(name: 'daily_test', value: false),
                    string(name: 'ghprbActualCommit', value: ghprbActualCommit),
                    string(name: 'ghprbCommentBody', value: ghprbCommentBody),
            ],
            wait: true, propagate: true)
}

def release_master() {
    commitID = get_sha("tispark", "master")
    version = "master"
    parallel(
            test1: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
            },

            test2: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.1")
            },

            test3: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.2")
            },

            test4: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.3")
            },
    )

    version = "release-6.5"
    parallel(
            test1: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
            },

            test2: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.1")
            },

            test3: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.2")
            },

            test4: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.3")
            },
    )

    version = "release-6.1"
    parallel(
            test1: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
            },

            test2: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.1")
            },

            test3: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.2")
            },

            test4: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.3")
            },
    )

    version = "release-5.4"
    parallel(
            test1: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
            },

            test2: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.1")
            },

            test3: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.2")
            },

            test4: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.3")
            }
    )
    version = "release-4.0"
    parallel(

            test1: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version")
            },

            test2: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.1")
            },

            test3: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.2")
            },

            test4: {
                test_base("master", commitID, "tidb=$version pd=$version tiflash=$version tikv=$version profile=spark-3.3")
            }
    )
    // tiflash test
    parallel(

            test1: {
                test_base("master", commitID, "tidb=master pd=master tiflash=master tikv=master test-flash=true")
            },


            test2: {
                test_base("master", commitID, "tidb=release-6.5 pd=release-6.5 tiflash=release-6.5 tikv=release-6.5 profile=spark-3.3 test-flash=true")
            },

            test3: {
                test_base("master", commitID, "tidb=release-6.1 pd=release-6.1 tiflash=release-6.1 tikv=release-6.1 profile=spark-3.2 test-flash=true")
            },

            test4: {
                test_base("master", commitID, "tidb=release-5.4 pd=release-5.4 tiflash=release-5.4 tikv=release-5.4 profile=spark-3.1 test-flash=true")
            },

            test5: {
                test_base("master", commitID, "tidb=release-4.0 pd=release-4.0 tiflash=release-4.0 tikv=release-4.0 test-flash=true")
            },

    )
}

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 600, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stage("Trigger job") {
        release_master()
    }
}