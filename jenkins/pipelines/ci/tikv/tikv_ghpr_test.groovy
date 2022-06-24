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
def CHUNK_COUNT = 2 // spawn two nodes to run tests

pod_image_param = ghprbTargetBranch
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
rust_image = "hub.pingcap.net/jenkins/tikv-cached-${pod_image_param}:latest"
println "ci image use ${rust_image}"

def run_test_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-tikv"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                        name: "4c", image: rust_image,
                        alwaysPullImage: true, ttyEnabled: true, privileged: true,
                        resourceRequestCpu: '4', resourceRequestMemory: '8Gi',
                        command: '/bin/sh -c', args: 'cat',
                    ),
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins/agent/memvolume', memory: true)
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            println "rust image: ${rust_image}"
            body()
        }
    }
}


try {

stage("PreCheck") {
    if (!params.force) {
        def label="${JOB_NAME}_pre_check_${BUILD_NUMBER}"
        podTemplate(name: label, label: label, 
            cloud: "kubernetes-ng",  idleMinutes: 0, namespace: "jenkins-tikv",
            workspaceVolume: emptyDirWorkspaceVolume(memory: true),
            containers: [
                containerTemplate(name: "2c", image: rust_image,
                    alwaysPullImage: true, privileged: true,
                    resourceRequestCpu: '2', resourceRequestMemory: '2Gi',
                    ttyEnabled: true, command: 'cat'),
            ],
        ) {
            node(label) {
                container("2c") {
                    notRun = sh(returnStatus: true, script: """
                    if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/ci_check/${JOB_NAME}/${ghprbActualCommit}; then exit 0; else exit 1; fi
                    """)
                }
            }
        }
    }

    if (notRun == 0) {
        println "the ${ghprbActualCommit} has been tested"
        currentBuild.result = 'SUCCESS'
        throw new RuntimeException("hasBeenTested")
    }
}

stage("Prepare") {
    def clippy = {
        def label="tikv_cached_${ghprbTargetBranch}_clippy_${BUILD_NUMBER}"
        podTemplate(name: label, label: label, 
            cloud: "kubernetes-ng",  idleMinutes: 0, namespace: "jenkins-tikv",
            workspaceVolume: emptyDirWorkspaceVolume(memory: true),
            containers: [
                containerTemplate(name: "4c", image: rust_image,
                    alwaysPullImage: true, privileged: true,
                    resourceRequestCpu: '4', resourceRequestMemory: '8Gi',
                    ttyEnabled: true, command: 'cat'),
            ],
        ) {
            node(label) {
                println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                def is_cached_lint_passed = false
                container("4c") {
                    is_cached_lint_passed = (sh(
                        label: 'Try to skip linting', returnStatus: true,
                        script: "curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/tikv_test/${ghprbActualCommit}/cached_lint_passed") == 0)
                    println "Skip linting: ${is_cached_lint_passed}"
                }

                if (!is_cached_lint_passed) {
                    container("4c") {
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

                        sh label: 'Run lint: format', script: """
                            cd \$HOME/tikv-src
                            export RUSTFLAGS=-Dwarnings
                            make format && git diff --quiet || (git diff; echo Please make format and run tests before creating a PR; exit 1)
                        """

                        sh label: 'Run lint: clippy', script: """
                            cd \$HOME/tikv-src
                            export RUSTFLAGS=-Dwarnings
                            export FAIL_POINT=1
                            export ROCKSDB_SYS_SSE=1
                            export RUST_BACKTRACE=1
                            export LOG_LEVEL=INFO
                            echo using gcc 8
                            source /opt/rh/devtoolset-8/enable
                            make clippy || (echo Please fix the clippy error; exit 1)
                        """

                        sh label: 'Post-lint: Save lint status', script: """
                        cd \$HOME/tikv-src
                        echo 1 > cached_lint_passed
                        curl -F tikv_test/${ghprbActualCommit}/cached_lint_passed=@cached_lint_passed ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
    }

    def build = {
        def label="tikv_cached_${ghprbTargetBranch}_build_${BUILD_NUMBER}"
        podTemplate(name: label, label: label,
            cloud: "kubernetes-ng",  idleMinutes: 0, namespace: "jenkins-tikv", instanceCap: 4,
            workspaceVolume: emptyDirWorkspaceVolume(memory: true),
            containers: [
                containerTemplate(name: "4c",
                    image: rust_image,
                    alwaysPullImage: true, privileged: true,
                    resourceRequestCpu: '4', resourceRequestMemory: '8Gi',
                    ttyEnabled: true, command: 'cat'),
            ],
        ) {
            node(label) {
                println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                def is_artifact_existed = false
                container("4c") {
                    is_artifact_existed = (sh(
                        label: 'Try to skip building test artifact', returnStatus: true,
                        script: "curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/tikv_test/${ghprbActualCommit}/cached_build_passed") == 0)
                    println "Skip building test artifact: ${is_artifact_existed}"
                }

                if (!is_artifact_existed) {
                    container("4c") {
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

                        sh label: 'Build test artifact', script: """
                        cd \$HOME/tikv-src
                        export RUSTFLAGS=-Dwarnings
                        export FAIL_POINT=1
                        export ROCKSDB_SYS_SSE=1
                        export RUST_BACKTRACE=1
                        export LOG_LEVEL=INFO
                        export CARGO_INCREMENTAL=0
                        export RUSTDOCFLAGS="-Z unstable-options --persist-doctests"
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                        set -o pipefail
                        # Build and generate a list of binaries
                        CARGO_TEST_COMMAND="nextest list" EXTRA_CARGO_ARGS="--message-format json --list-type binaries-only" make test | grep -E '^{.+}\$' > test.json
                        # Cargo metadata
                        cargo metadata --format-version 1 > test-metadata.json
                        """

                        sh label: 'Post-build: Upload test artifacts', script: """
                        cd \$HOME/tikv-src
                        python <<EOF
import sys
import subprocess
import json
import multiprocessing
def upload(bin):
    return subprocess.check_call(["curl", "-F", "tikv_test/${ghprbActualCommit}%s=@%s" % (bin, bin), "${FILE_SERVER_URL}/upload"])
merged_dict={ "rust-binaries": {} }
visited_files=set()
pool = multiprocessing.Pool(processes=4)
with open('test-binaries', 'w') as writer:
    with open('test.json', 'r') as f:
        for l in f:
            all = json.loads(l)
            binaries = all["rust-binaries"]
            if not "rust-build-meta" in merged_dict:
                merged_dict["rust-build-meta"] = all["rust-build-meta"]
            for (name, meta) in binaries.items():
                if meta["kind"] == "proc-macro":
                    continue
                bin = meta["binary-path"]
                
                if bin in visited_files:
                    continue
                visited_files.add(bin)
                merged_dict["rust-binaries"][name] = meta
                writer.write("%s\\n" % bin)
                pool.apply_async(upload, (bin,))
pool.close()
pool.join()
with open('test-binaries.json', 'w') as f:
    json.dump(merged_dict, f)
EOF
                        tar czf test-artifacts.tar.gz test-binaries test-binaries.json test-metadata.json Cargo.toml cmd src tests components .config `ls target/*/deps/*plugin.so 2>/dev/null`
                        curl -F tikv_test/${ghprbActualCommit}/test-artifacts.tar.gz=@test-artifacts.tar.gz ${FILE_SERVER_URL}/upload
                        echo 1 > cached_build_passed
                        curl -F tikv_test/${ghprbActualCommit}/cached_build_passed=@cached_build_passed ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
    }

    def prepare = [:]
    prepare["Lint"] = {
        clippy()
    }
    prepare["Build"] = {
        build()
    }
    parallel prepare
}


stage('Test') {
    def run_test = { chunk_suffix ->
        run_test_with_pod {
            dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                container("4c") {
                    println "debug command:\nkubectl -n jenkins-tikv exec -ti ${NODE_NAME} bash"
                    deleteDir()
                    timeout(15) {
                        sh """
                        # set -o pipefail
                        ln -s `pwd` \$HOME/tikv-src
                        uname -a
                        export RUSTFLAGS=-Dwarnings
                        export FAIL_POINT=1
                        export RUST_BACKTRACE=1
                        export MALLOC_CONF=prof:true,prof_active:false
                        export CI=1
                        export LOG_FILE=\$HOME/tikv-src/target/my_test.log
                        mkdir -p target/debug
                        curl -O ${FILE_SERVER_URL}/download/tikv_test/${ghprbActualCommit}/test-artifacts.tar.gz
                        tar xf test-artifacts.tar.gz
                        ls -la
                        for i in `cat test-binaries`; do
                            curl -o \$i ${FILE_SERVER_URL}/download/tikv_test/${ghprbActualCommit}/\$i --create-dirs;
                            chmod +x \$i;
                        done
                        if cargo nextest run -P ci --binaries-metadata test-binaries.json --cargo-metadata test-metadata.json --partition count:${chunk_suffix}/${CHUNK_COUNT} -j 7; then
                            echo "test pass"
                        else
                            # test failed
                            gdb -c core.* -batch -ex "info threads" -ex "thread apply all bt"
                            exit 1
                        fi
                        """
                    }
                }
            }
        }
    }

    def tests = [:]
    for (int i = 1; i <= CHUNK_COUNT; i ++) {
        def k = i
        tests["Part${k}"] = {
            run_test(k)
        }
    }

    parallel tests
}

currentBuild.result = "SUCCESS"
stage('Post-test') {
    def label="${JOB_NAME}_post_test_${BUILD_NUMBER}"
    podTemplate(name: label, label: label, 
        cloud: "kubernetes-ng",  idleMinutes: 0, namespace: "jenkins-tikv",
        workspaceVolume: emptyDirWorkspaceVolume(memory: true),
        containers: [
            containerTemplate(name: "2c", image: rust_image,
                alwaysPullImage: true, privileged: true,
                resourceRequestCpu: '2', resourceRequestMemory: '2Gi',
                ttyEnabled: true, command: 'cat'),
        ],
    ) {
        node(label) {
            container("2c") {
                sh """
                echo "done" > done
                curl -F ci_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
                """
            }
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci/test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tikv_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
