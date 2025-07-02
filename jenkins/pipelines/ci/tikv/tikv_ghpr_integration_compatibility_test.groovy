echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    echo "release test: ${params.containsKey("release_test")}"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tikv_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def PD_BRANCH = ghprbTargetBranch
def TIDB_BRANCH = ghprbTargetBranch
def TIDB_TEST_BRANCH = ghprbTargetBranch
def TIKV_OLD_BRANCH = "${ghprbTargetBranch}"

if (ghprbPullTitle.find("Bump version") != null) {
    currentBuild.result = 'SUCCESS'
    return
}

// parse tikv branch
def m1 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    PD_BRANCH = "${m1[0][1]}"
}
m1 = null
println "PD_BRANCH=${PD_BRANCH}"

// parse tidb branch
def m2 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    TIDB_BRANCH = "${m2[0][1]}"
}
m2 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

// parse tidb_test branch
def m3 = ghprbCommentBody =~ /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_TEST_BRANCH = "${m3[0][1]}"
}

m3 = null
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

// parse pd branch
def m4 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m4) {
    TIKV_OLD_BRANCH = "${m4[0][1]}"
}
m4 = null
println "TIKV_OLD_BRANCH=${TIKV_OLD_BRANCH}"

def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"

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

def run_with_pod(Closure body) {
    def label = POD_LABEL
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tikv"
    def jnlp_docker_image = "jenkins/inbound-agent:3148.v532a_7e715ee3-10"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "${POD_GO_IMAGE}", ttyEnabled: true,
                        resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]
                    )
            ]
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

try {
    stage('Prepare') {
        run_with_pod {
            def ws = pwd()
            deleteDir()

            container("golang") {
                def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"

                dir("go/src/github.com/pingcap/tidb") {
                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                        curl ${tidb_url} | tar xz
                        """
                    }
                }

                dir("go/src/github.com/PingCAP-QE/tidb-test") {
                    def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/PingCAP-QE/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                    def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                    def tidb_test_url = "${FILE_SERVER_URL}/download/builds/PingCAP-QE/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                    timeout(10) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                        curl ${tidb_test_url} | tar xz
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sT \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        cd compatible_test && GOPATH=${ws}/go ./build.sh
                        """
                    }
                }
            }

            stash includes: "go/src/github.com/PingCAP-QE/tidb-test/compatible_test/**", name: "compatible_test"
            deleteDir()
        }
    }

    stage('Integration Compatibility Test') {
        run_with_pod {
            def ws = pwd()
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            deleteDir()
            unstash 'compatible_test'

            dir("go/src/github.com/PingCAP-QE/tidb-test/compatible_test") {
                container("golang") {
                    def tikv_old_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_OLD_BRANCH}/sha1"
                    def tikv_old_sha1 = sh(returnStdout: true, script: "curl ${tikv_old_refs}").trim()
                    def tikv_old_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_old_sha1}/centos7/tikv-server.tar.gz"

                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                    def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                    pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

                    def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                    def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                    def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"


                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                        curl ${pd_url} | tar xz ./bin bin || true

                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                        curl ${tidb_url} | tar xz

                        mkdir -p ./tikv-old-src
                        cd ./tikv-old-src
                        echo ${tikv_old_url}
                        echo ${tikv_old_refs}
                        while ! curl --output /dev/null --silent --head --fail ${tikv_old_url}; do sleep 15; done
                        curl ${tikv_old_url} | tar xz
                        cd ..

                        mkdir -p ./tikv-src
                        cd ./tikv-src
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        sleep 15
                        (curl ${tikv_url} | tar xz) || (sleep 15 && curl ${tikv_url} | tar xz)
                        cd ..

                        mv tikv-old-src/bin/tikv-server bin/tikv-server-old
                        mv tikv-src/bin/tikv-server ./bin/tikv-server
                        """
                    }

                    timeout(10) {
                        try {
                            sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            rm -rf /tmp/tidb
                            set -e

                            mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                            export log_level=debug
                            TIKV_PATH=./bin/tikv-server \
                            TIDB_PATH=./bin/tidb-server \
                            PD_PATH=./bin/pd-server \
                            UPGRADE_PART=tikv \
                            NEW_BINARY=./bin/tikv-server \
                            OLD_BINARY=./bin/tikv-server-old \
                            GOPATH=${ws}/go ./test.sh 2>&1
                            """
                        } catch (err) {
                            sh "cat *.log"
                            throw err
                        } finally {
                            sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            set -e
                            """
                        }
                    }
                }
            }
            deleteDir()
        }
    }

    currentBuild.result = "SUCCESS"
}
catch(Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}
finally {
    println "currentBuild.result: ${currentBuild.result}"
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
                    string(name: 'TIKV_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tikv/integration-compatibility-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tikv_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
