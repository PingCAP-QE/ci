properties([
	pipelineTriggers([cron('H H * * *')])
])

def ghprbTargetBranch = params.getOrDefault("coverage_target_brach", "master")

def notRun = 1

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
stage("Cover") {
    def label="tikv_cached_${ghprbTargetBranch}_build"
    podTemplate(name: label, label: label,
        nodeSelector: "kubernetes.io/arch=amd64", instanceCap: 7,
        workspaceVolume: emptyDirWorkspaceVolume(memory: true),
        containers: [
            containerTemplate(name: 'rust', image: "hub.pingcap.net/jenkins/tikv-cached-${pod_image_param}:latest",
                alwaysPullImage: true, privileged: true,
                resourceRequestCpu: '2', resourceRequestMemory: '8Gi',
                ttyEnabled: true, command: 'cat'),
        ]) {
        node(label) {
            println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

            container("rust") {
                sh label: 'Prepare workspace', script: """
                    cd \$HOME/tikv-src
                    git fetch origin
                    git checkout -f origin/${ghprbTargetBranch}
                    rustup component add llvm-tools-preview
                    # used patched grcov to allow ignore corrupted profile data.
                    cargo install --locked grcov --git https://github.com/glorv/grcov --branch patched
                    grcov --version
                """

                def should_skip = sh (label: 'Check if can skip', script: """
                cd \$HOME/tikv-src
                if git log -1 | grep '\\[ci skip\\]'; then
                    exit 0
                fi
                ghprbActualCommit=`git rev-parse HEAD`
                curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/coverage_check/${JOB_NAME}/\$ghprbActualCommit
                """, returnStatus: true)
                if (should_skip == 0) {
                    throw new RuntimeException("ci skip")
                }

                sh label: 'Run test artifact', script: """
                cd \$HOME/tikv-src
                export FAIL_POINT=1
                export ROCKSDB_SYS_SSE=1
                export CARGO_INCREMENTAL=0
                export RUSTFLAGS="-C instrument-coverage"
                # use ci profile to enable test retry and timeout.
                export NEXTEST_PROFILE=ci

                echo using gcc 8
                source /opt/rh/devtoolset-8/enable

                env RUSTFLAGS="-C instrument-coverage" LLVM_PROFILE_FILE="tikv-%p-%m.profraw" FAIL_POINT=1 RUST_TEST_THREADS=1 EXTRA_CARGO_ARGS=--no-fail-fast make test_with_nextest || true
                """

                sh label: 'Post-build: Generating coverage', script: """
                cd \$HOME/tikv-src
                grcov . --binary-path ./target/debug/ -s . -t lcov --branch --ignore-not-existing --ignore "/*" --ignore "target/debug/*" -o lcov.info
                """

                withCredentials([string(credentialsId: 'TIKV_CODECOV_TOKEN', variable: 'CODECOV_TOKEN')]) {
                    timeout(180) {
                        sh label: 'Post-build: Uploading coverage', script: """
                        cd \$HOME/tikv-src
                        curl -OLs ${FILE_SERVER_URL}/download/cicd/ci-tools/codecov
                        chmod +x codecov
                        ghprbActualCommit=`git rev-parse HEAD`
                        ./codecov -f lcov.info -C \$ghprbActualCommit -B ${ghprbTargetBranch}
                        """
                    }
                }

                sh label: 'Mark coverage success', script: """
                cd \$HOME/tikv-src
                echo "done" > done
                ghprbActualCommit=`git rev-parse HEAD`
                curl -F coverage_check/${JOB_NAME}/\$ghprbActualCommit=@done ${FILE_SERVER_URL}/upload
                """
            }
        }
    }
}

currentBuild.result = "SUCCESS"
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
