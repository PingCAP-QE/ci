def notRun = 1
def CHUNK_COUNT = 2
def LEGACY_CHUNK_COUNT = 20
def use_legacy_test = false
def EXTRA_NEXTEST_ARGS = "-j 8"
def TEST_BINARIES_TMP_PATH = "archive-test-binaries"

def m1 = ghprbCommentBody =~ /retry\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    EXTRA_NEXTEST_ARGS = "${EXTRA_NEXTEST_ARGS} --retries ${m1[0][1]}"
}
m1 = null
println "EXTRA_NEXTEST_ARGS=${EXTRA_NEXTEST_ARGS}"

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

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""

podYAML = '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: util
      image: hub.pingcap.net/jenkins/ks3util
      args: ["sleep", "infinity"]
      resources:
        requests:
          cpu: "500m"
          memory: "500Mi"
        limits:
          cpu: "500m"
          memory: "500Mi"
  nodeSelector:
    enable-ci: true
    ci-nvme-high-performance: true
  tolerations:
  - key: dedicated
    operator: Equal
    value: test-infra
    effect: NoSchedule
'''

def upload_fileserver(local, remote){
    withCredentials(
        [file(credentialsId: 'ks3util-config', variable: 'KS3UTIL_CONF')]
    ) {
        sh "ks3util -c \$KS3UTIL_CONF cp -f $local ks3://ee-fileserver/download/${remote}"
    }
}

def download_fileserver(remote, local){
    withCredentials(
        [file(credentialsId: 'ks3util-config', variable: 'KS3UTIL_CONF')]
    ) {
        sh "ks3util -c \$KS3UTIL_CONF cp -f ks3://ee-fileserver/download/${remote} $local"
    }
}

def run_test_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tikv"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            yaml: podYAML,
            yamlMergeStrategy: merge(),
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                        name: "rust", image: rust_image,
                        alwaysPullImage: true, ttyEnabled: true, privileged: true,
                        resourceRequestCpu: '6', resourceRequestMemory: '8Gi',
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

def run_test_with_pod_legacy(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tikv"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            yaml: podYAML,
            yamlMergeStrategy: merge(),
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                        name: "rust", image: rust_image,
                        alwaysPullImage: true, ttyEnabled: true, privileged: true,
                        resourceRequestCpu: '2', resourceRequestMemory: '8Gi',
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
            cloud: "kubernetes-ksyun",  idleMinutes: 0, namespace: "jenkins-tikv",
            nodeSelector: "kubernetes.io/arch=amd64",
            yaml: podYAML, yamlMergeStrategy: merge(),
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
            cloud: "kubernetes-ksyun",  idleMinutes: 0, namespace: "jenkins-tikv",
            nodeSelector: "kubernetes.io/arch=amd64",
            yaml: podYAML, yamlMergeStrategy: merge(),
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
                            make format
                            git diff --quiet || (git diff; echo Please make format and run tests before creating a PR; exit 1)
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
                        container("util") {
                            dir("$WORKSPACE") {
                                sh label: 'Gen lint flag', script: """
                                    echo 1 > cached_lint_passed
                                """
                                upload_fileserver("cached_lint_passed", "tikv_test/${ghprbActualCommit}/cached_lint_passed")
                            }
                        }
                    }
                }
            }
        }
    }

    def build = {
        def label="tikv_cached_${ghprbTargetBranch}_build_${BUILD_NUMBER}"
        podTemplate(name: label, label: label,
            cloud: "kubernetes-ksyun",  idleMinutes: 0, namespace: "jenkins-tikv", instanceCap: 4,
            nodeSelector: "kubernetes.io/arch=amd64",
            yaml: podYAML, yamlMergeStrategy: merge(),
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
                    if (is_artifact_existed) {
                        use_legacy_test = !(sh(
                            label: 'Check if nextest', returnStatus: true,
                            script: "curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/tikv_test/${ghprbActualCommit}/is_nextest_build") == 0)
                    }
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
                        def make_status = sh (script: "cd \$HOME/tikv-src; make test_with_nextest --question", returnStatus: true)
                        def nextest_status = sh (script: "cargo nextest --help", returnStatus: true)
                        if (make_status != 2 && nextest_status == 0) {
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
                            CUSTOM_TEST_COMMAND="nextest list" EXTRA_CARGO_ARGS="--message-format json --list-type binaries-only" make test_with_nextest | grep -E '^{.+}\$' > test.json
                            # Cargo metadata
                            cargo metadata --format-version 1 > test-metadata.json
                            """

                            sh label: 'Post-build: Copy test artifacts', script: """
                            cd \$HOME/tikv-src
                            python <<EOF
#!/usr/bin/env python
import json
import os
import shutil

def move_file(full_source_path, src_base_dir="/home/jenkins/tikv-src", target_dir="archive-test-binaries"):
    # Function to copy files preserving the directory structure, excluding base_dir
    relative_path = os.path.relpath(full_source_path, src_base_dir)
    # Construct the full target path
    full_target_path = os.path.join(target_dir, relative_path)

    # Create the target directory if it doesn't exist
    target_path_dir = os.path.dirname(full_target_path)
    if not os.path.exists(target_path_dir):
        os.makedirs(target_path_dir)
    # Copy the file
    shutil.copy2(full_source_path, full_target_path)
    shutil.move(full_source_path, full_source_path)
    print("Moved %s to %s" % (full_source_path, full_target_path))

merged_dict={ "rust-binaries": {} }
visited_files=set()

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
                move_file(bin, )

with open('test-binaries.json', 'w') as f:
    json.dump(merged_dict, f)
EOF
                            ls -alh archive-test-binaries
                            tar -cvf archive-test-binaries.tar archive-test-binaries
                            ls -alh archive-test-binaries.tar

                            tar czf test-artifacts.tar.gz test-binaries test-binaries.json test-metadata.json Cargo.toml cmd src tests components .config `ls target/*/deps/*plugin.so 2>/dev/null`
                            ls -alh test-artifacts.tar.gz

                            mv test-artifacts.tar.gz archive-test-binaries.tar $WORKSPACE
                            """
                            container("util") {
                                dir("$WORKSPACE") {
                                    sh """
                                    ls -alh
                                    """
                                    upload_fileserver("archive-test-binaries.tar", "tikv_test/${ghprbActualCommit}/archive-test-binaries.tar")
                                    upload_fileserver("test-artifacts.tar.gz", "tikv_test/${ghprbActualCommit}/test-artifacts.tar.gz")
                                    sh label: 'Gen build flag', script: """
                                        echo 1 > cached_build_passed
                                        echo 1 > is_nextest_build
                                    """
                                    upload_fileserver("cached_build_passed", "tikv_test/${ghprbActualCommit}/cached_build_passed")
                                    upload_fileserver("is_nextest_build", "tikv_test/${ghprbActualCommit}/is_nextest_build")
                                }
                            }
                        } else {
                            use_legacy_test = true
                            sh label: 'Build test artifact', script: """
                            cd \$HOME/tikv-src
                            export RUSTFLAGS=-Dwarnings
                            export FAIL_POINT=1
                            export ROCKSDB_SYS_SSE=1
                            export RUST_BACKTRACE=1
                            export LOG_LEVEL=INFO
                            export CARGO_INCREMENTAL=0
                            echo using gcc 8
                            source /opt/rh/devtoolset-8/enable
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
                            sh label: 'Post-build: Upload test artifacts', script: """
                            cd \$HOME/tikv-src
                            python <<EOF
#!/usr/bin/env python
import subprocess
import json
import os
import shutil

chunk_count = ${LEGACY_CHUNK_COUNT}
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

# def upload(bin, binary_tmp_path="test-binaries-tmp"):
#     return subprocess.check_call(["cp", bin, binary_tmp_path])

def move_file(full_source_path, src_base_dir="/home/jenkins/tikv-src", target_dir="archive-test-binaries"):
    # Function to copy files preserving the directory structure, excluding base_dir
    relative_path = os.path.relpath(full_source_path, src_base_dir)
    # Construct the full target path
    full_target_path = os.path.join(target_dir, relative_path)

    # Create the target directory if it doesn't exist
    target_path_dir = os.path.dirname(full_target_path)
    if not os.path.exists(target_path_dir):
        os.makedirs(target_path_dir)
    # Copy the file
    shutil.copy2(full_source_path, full_target_path)
    shutil.move(full_source_path, full_source_path)
    print("Moved %s to %s" % (full_source_path, full_target_path))


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

chunk_score = total_score / chunk_count + 1
current_chunk_score=0
part=1
writer = write_to(part)
scores.sort(key=lambda t: t[0])

for bin, cases, bin_score in scores:
    move_file(bin)
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

writer.close()
EOF
                            ls -alh archive-test-binaries
                            tar -cvf archive-test-binaries.tar archive-test-binaries
                            ls -alh archive-test-binaries.tar

                            chmod a+x test-chunk-*
                            tar czf test-artifacts.tar.gz test-chunk-* src tests components `ls target/*/deps/*plugin.so 2>/dev/null`
                            ls -alh test-artifacts.tar.gz

                            mv archive-test-binaries.tar test-artifacts.tar.gz $WORKSPACE
                            """
                            container("util") {
                                dir("$WORKSPACE") {
                                    sh """
                                    pwd && ls -alh
                                    """
                                    upload_fileserver("archive-test-binaries.tar", "tikv_test/${ghprbActualCommit}/archive-test-binaries.tar")
                                    upload_fileserver("test-artifacts.tar.gz", "tikv_test/${ghprbActualCommit}/test-artifacts.tar.gz")
                                    sh label: 'Gen build flag', script: """
                                        echo 1 > cached_build_passed
                                    """
                                    upload_fileserver("cached_build_passed", "tikv_test/${ghprbActualCommit}/cached_build_passed")
                                }
                            }
                        }
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
                container("rust") {
                    println "debug command:\nkubectl -n jenkins-tikv exec -ti ${NODE_NAME} bash"
                    deleteDir()
                    try {
                        container("util") {
                            sh label: 'os info', script:"""
                            # set -o pipefail
                            ln -s `pwd` \$HOME/tikv-src
                            uname -a
                            mkdir -p target/debug
                            """
                            sh """
                            pwd && ls -alh
                            """
                            download_fileserver("tikv_test/${ghprbActualCommit}/test-artifacts.tar.gz", "test-artifacts.tar.gz")
                            download_fileserver("tikv_test/${ghprbActualCommit}/archive-test-binaries.tar", "archive-test-binaries.tar")
                            sh """
                            ls -alh test-artifacts.tar.gz archive-test-binaries.tar
                            tar xf test-artifacts.tar.gz && rm test-artifacts.tar.gz
                            tar xf archive-test-binaries.tar --strip-components=1 && rm archive-test-binaries.tar
                            ls -la
                            ls -alh target/debug/deps/
                            chown -R 1000:1000 target/
                            """
                        }
                        timeout(15) {
                            sh label: 'Run test', script: """
                            ln -s `pwd` \$HOME/tikv-src
                            ls -alh \$HOME/tikv-src
                            ls -alh /home/jenkins/tikv-src/
                            ls -alh /home/jenkins/tikv-src/target/debug/deps/
                            pwd && ls -alh
                            export RUSTFLAGS=-Dwarnings
                            export FAIL_POINT=1
                            export RUST_BACKTRACE=1
                            export MALLOC_CONF=prof:true,prof_active:false
                            export CI=1
                            export LOG_FILE=\$HOME/tikv-src/target/my_test.log
                            if cargo nextest run -P ci --binaries-metadata test-binaries.json --cargo-metadata test-metadata.json --partition count:${chunk_suffix}/${CHUNK_COUNT} ${EXTRA_NEXTEST_ARGS}; then
                                echo "test pass"
                            else
                                # test failed
                                gdb -c core.* -batch -ex "info threads" -ex "thread apply all bt"
                                exit 1
                            fi
                            """
                        }
                    } catch (Exception e) {
                        throw e
                    } finally {
                        junit testResults: "**/target/nextest/ci/junit.xml", allowEmptyResults: true
                    }
                }
            }
        }
    }

    def run_test_legacy = { chunk_suffix ->
        run_test_with_pod_legacy {
            dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                retry(3) {
                    container("rust") {
                        println "debug command:\nkubectl -n jenkins-tikv exec -ti ${NODE_NAME} bash"
                        deleteDir()
                        timeout(15) {
                            container("util") {
                                sh label: 'os info', script:"""
                                # set -o pipefail
                                uname -a
                                mkdir -p target/debug
                                """
                                sh """
                                pwd && ls -alh
                                """
                                download_fileserver("tikv_test/${ghprbActualCommit}/test-artifacts.tar.gz", "test-artifacts.tar.gz")
                                download_fileserver("tikv_test/${ghprbActualCommit}/archive-test-binaries.tar", "archive-test-binaries.tar")
                                sh """
                                ls -alh test-artifacts.tar.gz archive-test-binaries.tar
                                tar xf test-artifacts.tar.gz && rm test-artifacts.tar.gz
                                tar xf archive-test-binaries.tar --strip-components=1 && rm archive-test-binaries.tar
                                ls -la
                                ls -alh target/debug/deps/
                                chown -R 1000:1000 target/
                                """
                            }
                            sh label: 'Run test', script:"""
                            # set -o pipefail
                            ln -sf `pwd` \$HOME/tikv-src
                            ls -alh \$HOME/tikv-src
                            ls -alh /home/jenkins/tikv-src/
                            ls -alh /home/jenkins/tikv-src/target/debug/deps/
                            uname -a
                            export RUSTFLAGS=-Dwarnings
                            export FAIL_POINT=1
                            export RUST_BACKTRACE=1
                            export MALLOC_CONF=prof:true,prof_active:false
                            if [[ ! -f test-chunk-${chunk_suffix} ]]; then
                                if [[ ${chunk_suffix} -eq ${LEGACY_CHUNK_COUNT} ]]; then
                                    exit
                                else
                                    echo test-chunk-${chunk_suffix} not found
                                    exit 1
                                fi
                            fi
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
    }

    def tests = [:]
    if (use_legacy_test) {
        for (int i = 1; i <= LEGACY_CHUNK_COUNT; i ++) {
            def k = i
            tests["Part${k}"] = {
                run_test_legacy(k)
            }
        }
    } else {
        for (int i = 1; i <= CHUNK_COUNT; i ++) {
            def k = i
            tests["Part${k}"] = {
                run_test(k)
            }
        }
    }

    parallel tests
}

currentBuild.result = "SUCCESS"
stage('Post-test') {
    def label="${JOB_NAME}_post_test_${BUILD_NUMBER}"
    podTemplate(name: label, label: label,
        cloud: "kubernetes-ksyun",  idleMinutes: 0, namespace: "jenkins-tikv",
        nodeSelector: "kubernetes.io/arch=amd64",
        yaml: podYAML, yamlMergeStrategy: merge(),
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
} finally {
    stage("upload-pipeline-data") {
        taskFinishTime = System.currentTimeMillis()
        build job: 'upload-pipelinerun-data',
            wait: false,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_URL', value: "${RUN_DISPLAY_URL}"],
                    [$class: 'StringParameterValue', name: 'REPO', value: "tikv/tikv"],
                    [$class: 'StringParameterValue', name: 'COMMIT_ID', value: ghprbActualCommit],
                    [$class: 'StringParameterValue', name: 'TARGET_BRANCH', value: ghprbTargetBranch],
                    [$class: 'StringParameterValue', name: 'JUNIT_REPORT_URL', value: resultDownloadPath],
                    [$class: 'StringParameterValue', name: 'PULL_REQUEST', value: ghprbPullId],
                    [$class: 'StringParameterValue', name: 'PULL_REQUEST_AUTHOR', value: params.getOrDefault("ghprbPullAuthorLogin", "default")],
                    [$class: 'StringParameterValue', name: 'JOB_TRIGGER', value: params.getOrDefault("ghprbPullAuthorLogin", "default")],
                    [$class: 'StringParameterValue', name: 'TRIGGER_COMMENT_BODY', value: params.getOrDefault("ghprbCommentBody", "default")],
                    [$class: 'StringParameterValue', name: 'JOB_RESULT_SUMMARY', value: ""],
                    [$class: 'StringParameterValue', name: 'JOB_START_TIME', value: "${taskStartTimeInMillis}"],
                    [$class: 'StringParameterValue', name: 'JOB_END_TIME', value: "${taskFinishTime}"],
                    [$class: 'StringParameterValue', name: 'POD_READY_TIME', value: ""],
                    [$class: 'StringParameterValue', name: 'CPU_REQUEST', value: "2000m"],
                    [$class: 'StringParameterValue', name: 'MEMORY_REQUEST', value: "8Gi"],
                    [$class: 'StringParameterValue', name: 'JOB_STATE', value: currentBuild.result],
                    [$class: 'StringParameterValue', name: 'JENKINS_BUILD_NUMBER', value: "${BUILD_NUMBER}"],
        ]
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
