echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    echo "release test: ${params.containsKey("release_test")}"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tikv_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", 0)
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def notRun = 1
def chunk_count = 20

def pod_image_param = ghprbTargetBranch


// example hotfix branch  release-4.0-20210724 | example release-5.1-hotfix-tiflash-patch1
// remove suffix "-20210724", only use "release-4.0"
if (ghprbTargetBranch.startsWith("release-") && ghprbTargetBranch.split("-").size() >= 3 ) {
    println "tikv hotfix branch: ${ghprbTargetBranch}"
    def k = ghprbTargetBranch.indexOf("-", ghprbTargetBranch.indexOf("-") + 1)
    ghprbTargetBranch = ghprbTargetBranch.substring(0, k)
    pod_image_param = ghprbTargetBranch
}

if (!ghprbTargetBranch.startsWith("release-")) {
    pod_image_param = "master"
}
println "ci image use  hub.pingcap.net/jenkins/tikv-cached-${pod_image_param}:latest"


try {
stage("PreCheck") {
    if (!params.force) {
        node("${GO_BUILD_SLAVE}"){
            container("golang") {
                notRun = sh(returnStatus: true, script: """
                if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/coverage_check/${JOB_NAME}/${ghprbActualCommit}; then exit 0; else exit 1; fi
                """)
            }
        }
    }

    if (notRun == 0) {
        println "the ${ghprbActualCommit} has been tested"
        currentBuild.result = 'SUCCESS'
        throw new RuntimeException("hasBeenTested")
    }
}

stage("Cover") {
    def label="tikv_cached_${ghprbTargetBranch}_build"
    podTemplate(name: label, label: label,
        nodeSelector: 'role_type=slave', instanceCap: 7,
        workspaceVolume: emptyDirWorkspaceVolume(memory: true),
        containers: [
            containerTemplate(name: 'rust', image: "hub.pingcap.net/jenkins/tikv-cached-${pod_image_param}:latest",
                alwaysPullImage: true, privileged: true,
                resourceRequestCpu: '4', resourceRequestMemory: '8Gi',
                ttyEnabled: true, command: 'cat'),
        ]) {
        node(label) {
            println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

            container("rust") {
                sh label: 'Prepare workspace', script: """
                    cd \$HOME/tikv-src
                    if [[ "${ghprbPullId}" == 0 ]]; then
                        git fetch origin
                    else
                        git fetch origin refs/pull/${ghprbPullId}/head
                    fi
                    git checkout -f ${ghprbActualCommit}
                """

                def should_skip = sh (script: "cd \$HOME/tikv-src; git log -1 | grep '\\[ci skip\\]'", returnStatus: true)
                if (should_skip == 0) {
                    throw new RuntimeException("ci skip")
                }

                sh label: 'Run test artifact', script: """
                cd \$HOME/tikv-src
                export FAIL_POINT=1
                export ROCKSDB_SYS_SSE=1
                export CARGO_INCREMENTAL=0
                export RUSTFLAGS="-Zinstrument-coverage"

                grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                    echo using gcc 8
                    source /opt/rh/devtoolset-8/enable
                fi

                env RUSTFLAGS="-Zinstrument-coverage" LLVM_PROFILE_FILE="tikv-%p-%m.profraw" FAIL_POINT=1 RUST_TEST_THREADS=1 EXTRA_CARGO_ARGS=--no-fail-fast make test
                """

                sh label: 'Post-build: Upload coverage', script: """
                cd \$HOME/tikv-src
                curl -Os https://github.com/mozilla/grcov/releases/download/v0.8.2/grcov-linux-x86_64.tar.bz2
                tar xf grcov-linux-x86_64.tar.bz2
                ./grcov . --binary-path ./target/debug/ -s . -t lcov --branch --ignore-not-existing --ignore "/*" --ignore "target/debug/*" -o lcov.info

                curl -Os https://uploader.codecov.io/latest/linux/codecov
                chmod +x codecov
                if [[ "${ghprbPullId}" == 0 ]]; then
                    ./codecov -f lcov.info -C ${ghprbActualCommit} -P ${ghprbPullId}
                else
                    ./codecov -f lcov.info -C ${ghprbActualCommit} -B ${ghprbTargetBranch}
                fi
                """
            }
        }
    }
}

currentBuild.result = "SUCCESS"
stage('Post-cover') {
    node("${GO_BUILD_SLAVE}"){
        container("golang"){
            sh """
            echo "done" > done
            curl -F coverage_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
            """
        }
    }
}
} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    currentBuild.result = "ABORTED"
} catch (Exception e) {
    errorDescription = e.getMessage()
    if (errorDescription == "hasBeenTested" || errorDescription == "ci skip") {
        currentBuild.result = 'SUCCESS'
    } else {
        currentBuild.result = "FAILURE"
        echo "${e}"
    }
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci/coverage'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tikv_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
