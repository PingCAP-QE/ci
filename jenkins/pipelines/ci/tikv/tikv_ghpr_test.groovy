def notRun = 1
def chunk_count = 20

if (ghprbPullTitle.find("Bump version") != null) {
    currentBuild.result = 'SUCCESS'
    return
}
try {
stage("PreCheck") {
    if (!params.force) {
        node("${GO_BUILD_SLAVE}"){
            container("golang") {
                notRun = sh(returnStatus: true, script: """
                if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/ci_check/${JOB_NAME}/${ghprbActualCommit}; then exit 0; else exit 1; fi
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

stage("Prepare") {
    def clippy = {
    def label="tikv_cached_${ghprbTargetBranch}_clippy"
    podTemplate(name: label, label: label,
        nodeSelector: 'role_type=slave', instanceCap: 3,
        workspaceVolume: emptyDirWorkspaceVolume(memory: true),
        containers: [
            containerTemplate(name: 'rust', image: "hub.pingcap.net/jenkins/tikv-cached-${ghprbTargetBranch}:latest",
                alwaysPullImage: true, privileged: true,
                resourceRequestCpu: '4', resourceRequestMemory: '8Gi',
                ttyEnabled: true, command: 'cat'),
        ]) {
        node(label) {
            println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

            def is_cached_lint_passed = false
            container("rust") {
                is_cached_lint_passed = (sh(label: 'Try to skip linting', returnStatus: true, script: 'curl --output /dev/null --silent --head --fail ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/cached_lint_passed') == 0)
                println "Skip linting: ${is_cached_lint_passed}"
            }

            if (!is_cached_lint_passed) {
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
                        grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                        if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                            echo using gcc 8
                            source /opt/rh/devtoolset-8/enable
                        fi
                        make clippy || (echo Please fix the clippy error; exit 1)
                    """

                    sh label: 'Post-lint: Save lint status', script: """
                    cd \$HOME/tikv-src
                    echo 1 > cached_lint_passed
                    curl -F tikv_test/${ghprbActualCommit}/cached_lint_passed=@cached_lint_passed ${FILE_SERVER2_URL}/upload
                    """
                }
            }
        }
    }
    }

    def build = {
        def label="tikv_cached_${ghprbTargetBranch}_build"
    podTemplate(name: label, label: label,
        nodeSelector: 'role_type=slave', instanceCap: 7,
        workspaceVolume: emptyDirWorkspaceVolume(memory: true),
        containers: [
            containerTemplate(name: 'rust', image: "hub.pingcap.net/jenkins/tikv-cached-${ghprbTargetBranch}:latest",
                alwaysPullImage: true, privileged: true,
                resourceRequestCpu: '4', resourceRequestMemory: '8Gi',
                ttyEnabled: true, command: 'cat'),
        ]) {
        node(label) {
            println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

            def is_artifact_existed = false
            container("rust") {
                is_artifact_existed = (sh(label: 'Try to skip building test artifact', returnStatus: true, script: 'curl --output /dev/null --silent --head --fail ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/cached_build_passed') == 0)
                println "Skip building test artifact: ${is_artifact_existed}"
            }

            if (!is_artifact_existed) {
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

                    sh label: 'Build test artifact', script: """
                    cd \$HOME/tikv-src
                    export RUSTFLAGS=-Dwarnings
                    export FAIL_POINT=1
                    export ROCKSDB_SYS_SSE=1
                    export RUST_BACKTRACE=1
                    export LOG_LEVEL=INFO
                    export CARGO_INCREMENTAL=0

                    grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                    if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                    fi

                    set -o pipefail

                    test="test"
                    if grep ci_test Makefile; then
                        test="ci_test"
                    fi
                    if EXTRA_CARGO_ARGS="--no-run --message-format=json" make ci_doc_test test | grep -E '^{.+}\$' > test.json ; then
                        set +o pipefail
                    else
                        if EXTRA_CARGO_ARGS="--no-run --message-format=json" make test | grep -E '^{.+}\$' > test.json ; then
                            set +o pipefail
                        else
                            EXTRA_CARGO_ARGS="--no-run" make test
                            exit 1
                        fi
                    fi
                    """

                    sh label: 'Post-build: Check SSE instructions', script: """
                    cd \$HOME/tikv-src
                    if [ -f scripts/check-sse4_2.sh ]; then
                        sh scripts/check-sse4_2.sh
                    fi
                    """

                    sh label: 'Post-build: Check Jemalloc linking', script: """
                    cd \$HOME/tikv-src
                    if [ -f scripts/check-bins-for-jemalloc.sh ]; then
                        sh scripts/check-bins-for-jemalloc.sh
                    fi
                    """

                    sh label: 'Post-build: Upload test artifacts', script: """
                    cd \$HOME/tikv-src
                    python <<EOF
import sys
import subprocess
import json
import multiprocessing
chunk_count = ${chunk_count}
scores = list()
def score(bin, l):
    if "integration" in bin or "failpoint" in bin:
        if "test_split_region" in l:
            return 50
        else:
            return 20
    elif "deps/tikv-" in bin:
        return 10
    else:
        return 1
def write_to(part):
    f = open("test-chunk-%d" % part, 'w')
    f.write("#/usr/bin/env bash\\n")
    f.write("set -ex\\n")
    return f
def upload(bin):
    return subprocess.check_call(["curl", "-F", "tikv_test/${ghprbActualCommit}%s=@%s" % (bin, bin), "${FILE_SERVER2_URL}/upload"])
total_score=0
visited_files=set()
with open('test.json', 'r') as f:
    for l in f:
        if "proc-macro" in l:
            continue
        meta = json.loads(l)
        if "profile" in meta and meta["profile"]["test"]:
            for bin in meta["filenames"]:
                if bin in visited_files:
                    continue
                visited_files.add(bin)
                cases = subprocess.check_output([bin, '--list']).splitlines()
                if len(cases) < 2:
                    continue
                cases = list(c[:c.index(': ')] for c in cases if ': ' in c)
                bin_score = sum(score(bin, c) for c in cases)
                scores.append((bin, cases, bin_score))
                total_score += bin_score
chunk_score = total_score / chunk_count
current_chunk_score=0
part=0
writer = write_to(part)
pool = multiprocessing.Pool(processes=2)
scores.sort(key=lambda t: t[0])
for bin, cases, bin_score in scores:
    pool.apply_async(upload, (bin,))
    if current_chunk_score + bin_score <= chunk_score:
        writer.write("%s --test --nocapture\\n" % bin)
        current_chunk_score += bin_score
        continue
    batch_cases = list()
    for c in cases:
        c_score = score(bin, c)
        if current_chunk_score + c_score > chunk_score and part < chunk_count and batch_cases:
            writer.write("%s --test --nocapture --exact %s\\n" % (bin, ' '.join(batch_cases)))
            current_chunk_score = 0
            part += 1
            writer.close()
            writer = write_to(part)
            batch_cases = list()
        batch_cases.append(c)
        current_chunk_score += c_score
    if batch_cases:
        writer.write("%s --test --nocapture --exact %s\\n" % (bin, ' '.join(batch_cases)))
pool.close()
writer.close()
pool.join()
EOF
                    chmod a+x test-chunk-*
                    tar czf test-chunk.tar.gz test-chunk-* src tests components `ls target/*/deps/*plugin.so 2>/dev/null`
                    curl -F tikv_test/${ghprbActualCommit}/test-chunk.tar.gz=@test-chunk.tar.gz ${FILE_SERVER2_URL}/upload
                    echo 1 > cached_build_passed
                    curl -F tikv_test/${ghprbActualCommit}/cached_build_passed=@cached_build_passed ${FILE_SERVER2_URL}/upload
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
        node("${GO_TEST_SLAVE}") {
            dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                container("golang") {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

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
                        mkdir -p target/debug
                        curl -O ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/test-chunk.tar.gz
                        tar xf test-chunk.tar.gz
                        ls -la
                        if [[ ! -f test-chunk-${chunk_suffix} ]]; then
                            if [[ ${chunk_suffix} -eq ${chunk_count} ]]; then
                            exit
                            else
                            echo test-chunk-${chunk_suffix} not found
                            exit 1
                            fi
                        fi
                        for i in `cat test-chunk-${chunk_suffix} | cut -d ' ' -f 1 | sort -u`; do
                            curl -o \$i ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/\$i --create-dirs;
                            chmod +x \$i;
                        done
                        CI=1 LOG_FILE=target/my_test.log RUST_TEST_THREADS=1 RUST_BACKTRACE=1 ./test-chunk-${chunk_suffix} 2>&1 | tee tests.out
                        chunk_count=`grep nocapture test-chunk-${chunk_suffix} | wc -l`
                        ok_count=`grep "test result: ok" tests.out | wc -l`
                        if [ "\$chunk_count" -eq "\$ok_count" ]; then
                            echo "test pass"
                        else
                            # test failed
                            grep "^    " tests.out | tr -d '\\r'  | grep :: | xargs -I@ awk 'BEGIN{print "---- log for @ ----\\n"}/start, name: @/{flag=1}{if (flag==1) print substr(\$0, length(\$1) + 2)}/end, name: @/{flag=0}END{print ""}' target/my_test.log
                            awk '/^failures/{flag=1}/^test result:/{flag=0}flag' tests.out
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
    for (int i = 0; i <= chunk_count; i ++) {
        def k = i
        tests["Part${k}"] = {
            run_test(k)
        }
    }

    parallel tests
}
currentBuild.result = "SUCCESS"
stage('Post-test') {
    node("${GO_BUILD_SLAVE}"){
        container("golang"){
            sh """
            echo "done" > done
            curl -F ci_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
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


stage("upload status") {
    node("master") {
        println currentBuild.result
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
    }
}
