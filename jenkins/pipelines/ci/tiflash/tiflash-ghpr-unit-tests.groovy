if (params.containsKey("release_test")) {
    echo "this build is triggered by qa for release testing"
}

def coverage() {
    if (ghprbTargetBranch.contains("release-5.2")
     || ghprbTargetBranch.contains("release-5.1")
     || ghprbTargetBranch.contains("release-5.0")
     || ghprbTargetBranch.contains("release-4.0")
     || ghprbTargetBranch.contains("release-3.0")) {
        return false
    }

    // The coverage is not useful for each PR for now, as there is no diff information
    // and it is just hard for the author to know how good the coverage is.
    // Let's disable it for the moment, until we can provide more effective data.
    return false
}

def page_tools() {
    if (ghprbTargetBranch.contains("release-5.1")
     || ghprbTargetBranch.contains("release-5.0")
     || ghprbTargetBranch.contains("release-4.0")
     || ghprbTargetBranch.contains("release-3.0")) {
        return false
    }
    return true
}

def IDENTIFIER = "tiflash-ut-${ghprbTargetBranch}-${ghprbPullId}-${BUILD_NUMBER}"
def parameters = [
    string(name: "ARCH", value: "amd64"),
    string(name: "OS", value: "linux"),
    string(name: "CMAKE_BUILD_TYPE", value: "Debug"),
    string(name: "TARGET_BRANCH", value: ghprbTargetBranch),
    string(name: "TARGET_PULL_REQUEST", value: ghprbPullId),
    string(name: "TARGET_COMMIT_HASH", value: ghprbActualCommit),
    [$class: 'BooleanParameterValue', name: 'BUILD_TIFLASH', value: false],
    [$class: 'BooleanParameterValue', name: 'BUILD_PAGE_TOOLS', value: page_tools()],
    [$class: 'BooleanParameterValue', name: 'BUILD_TESTS', value: true],
    [$class: 'BooleanParameterValue', name: 'ENABLE_CCACHE', value: true],
    [$class: 'BooleanParameterValue', name: 'ENABLE_PROXY_CACHE', value: true],
    [$class: 'BooleanParameterValue', name: 'UPDATE_CCACHE', value: false],
    [$class: 'BooleanParameterValue', name: 'UPDATE_PROXY_CACHE', value: false],
    [$class: 'BooleanParameterValue', name: 'ENABLE_STATIC_ANALYSIS', value: false],
    [$class: 'BooleanParameterValue', name: 'ENABLE_FORMAT_CHECK', value: false],
    [$class: 'BooleanParameterValue', name: 'ENABLE_COVERAGE', value: coverage()],
    [$class: 'BooleanParameterValue', name: 'PUSH_MESSAGE', value: false],
    [$class: 'BooleanParameterValue', name: 'DEBUG_WITHOUT_DEBUG_INFO', value: true],
    [$class: 'BooleanParameterValue', name: 'ARCHIVE_ARTIFACTS', value: true],
    [$class: 'BooleanParameterValue', name: 'ARCHIVE_BUILD_DATA', value: true],
    [$class: 'BooleanParameterValue', name: 'ENABLE_FAILPOINTS', value: true],
]


def runBuilderClosure(label, image, Closure body) {
    podTemplate(name: label, label: label, instanceCap: 15, cloud: "kubernetes-ksyun", namespace: "jenkins-tiflash", idleMinutes: 0,nodeSelector: "kubernetes.io/arch=amd64",
        containers: [
            containerTemplate(name: 'runner', image: image,
                alwaysPullImage: true, ttyEnabled: true, command: 'cat',
                resourceRequestCpu: '12000m', resourceRequestMemory: '32Gi',
                resourceLimitCpu: '12000m', resourceLimitMemory: '32Gi'),
        ],
        volumes: [
            // TODO use s3 cache instead of nfs
            nfsVolume(mountPath: '/home/jenkins/agent/dependency', serverAddress: "${NFS_SERVER_ADDRESS}",
                serverPath: '/data/nvme1n1/nfs/tiflash/dependency', readOnly: true),
            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: "${NFS_SERVER_ADDRESS}",
                serverPath: '/data/nvme1n1/nfs/git', readOnly: true),
            emptyDirVolume(mountPath: '/tmp-memfs', memory: true),
        ],
        hostNetwork: false
    ) {
        node(label) {
            container('runner') {
                body()
            }
        }
    }
}

def dispatchRunEnv(toolchain, identifier, Closure body) {
    if (toolchain == 'llvm') {
        def image_tag_suffix = ""
        if (fileExists("/home/jenkins/agent/workspace/tiflash-build-common/tiflash/.toolchain.yml")) {
            image_tag_suffix = readYaml(file: "/home/jenkins/agent/workspace/tiflash-build-common/tiflash/.toolchain.yml").image_tag_suffix
        }
        runBuilderClosure(identifier, "hub.pingcap.net/tiflash/tiflash-llvm-base:amd64${image_tag_suffix}", body)
    } else {
        runBuilderClosure(identifier, "hub.pingcap.net/tiflash/tiflash-builder-ci", body)
    }
}


def existsBuildCache() {
    def status = ""
    try {
        def api = "https://ci.pingcap.net/job/tiflash-build-common/api/xml?tree=allBuilds[result,number,building,actions[parameters[name,value]]]&xpath=(//allBuild[action[parameter[name=%22TARGET_COMMIT_HASH%22%20and%20value=%22${ghprbActualCommit}%22]%20and%20parameter[name=%22BUILD_TESTS%22%20and%20value=%22true%22]]])[1]"
        def response = httpRequest api
        def content = response.getContent()
        if (content.contains('<building>false</building>') && content.contains('<result>SUCCESS</result>')) {
            def match = (content =~ /<number>(\d+)<\/number>/)
            match.find()
            status = match.group(1)
        }
    } catch (Exception e) {
        println "error: ${e}"
        status = ""
    }
    return status
}

def prepareArtifacts(built, get_toolchain) {
    def filter = "*"
    if (get_toolchain) {
        filter = "toolchain"
    }
    copyArtifacts(
        projectName: 'tiflash-build-common',
        selector: specific("${built}"),
        filter: filter,
        optional: false
    )
}

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}-for-ut"
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tiflash"
    podTemplate(label: label,
        cloud: cloud,
        namespace: namespace,
        idleMinutes: 0,
        nodeSelector: "kubernetes.io/arch=amd64",
        containers: [
            containerTemplate(
                name: 'golang', alwaysPullImage: true,
                image: "hub.pingcap.net/jenkins/centos7_golang-1.18:latest", ttyEnabled: true,
                resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                command: '/bin/sh -c', args: 'cat',
                envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]
            )
        ],
        volumes: [
            // TODO use s3 cache instead of nfs
            emptyDirVolume(mountPath: '/tmp', memory: false),
            emptyDirVolume(mountPath: '/home/jenkins', memory: false),
            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: "${NFS_SERVER_ADDRESS}",
                serverPath: '/data/nvme1n1/nfs/git', readOnly: true),
        ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            timeout(unit: 'MINUTES', time: 60) { body() }
        }
    }
}

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""

try {
run_with_pod {
    def toolchain = null
    def built = null
    stage('Build') {
        def cache = existsBuildCache()
        if (!cache) {
            def task = build(
                job: "tiflash-build-common",
                wait: true,
                propagate: false,
                parameters: parameters
            )
            if (task.getResult() != 'SUCCESS') {
                error "Build failed, see: https://ci.pingcap.net/blue/organizations/jenkins/tiflash-build-common/detail/tiflash-build-common/${task.number}/pipeline"
            } else {
                echo "Built at: https://ci.pingcap.net/blue/organizations/jenkins/tiflash-build-common/detail/tiflash-build-common/${task.number}/pipeline"
            }
            built = task.number
        } else {
            echo "Using cached build: https://ci.pingcap.net/blue/organizations/jenkins/tiflash-build-common/detail/tiflash-build-common/${cache}/pipeline"
            built = cache.toInteger()
        }
        prepareArtifacts(built, true)
        toolchain = readFile(file: 'toolchain').trim()
        echo "Built with ${toolchain}"
    }

    stage('Checkout') {
        dir("/home/jenkins/agent/workspace/tiflash-build-common/tiflash") {
            def cache_path = "/home/jenkins/agent/ci-cached-code-daily/src-tics.tar.gz"
            if (fileExists(cache_path)) {
                println "get code from nfs to reduce clone time"
                sh """
                set +x
                cp -R ${cache_path}  ./
                tar -xzf ${cache_path} --strip-components=1
                rm -f src-tics.tar.gz
                chown -R 1000:1000 ./
                set -x
                """
            }
            checkout(changelog: false, poll: false, scm: [
                $class                           : "GitSCM",
                branches                         : [
                        [name: ghprbActualCommit],
                ],
                userRemoteConfigs                : [
                        [
                                url          : "git@github.com:pingcap/tiflash.git",
                                refspec      : "+refs/heads/*:refs/remotes/origin/* +refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                credentialsId: "github-sre-bot-ssh",
                        ]
                ],
                extensions                       : [
                        [$class             : 'SubmoduleOption',
                        disableSubmodules  : toolchain == 'llvm',
                        parentCredentials  : true,
                        recursiveSubmodules: true,
                        trackingSubmodules : false,
                        reference          : ''],
                        [$class: 'PruneStaleBranch'],
                        [$class: 'CleanBeforeCheckout'],
                ],
                doGenerateSubmoduleConfigurations: false,
            ])
        }
        dir("/home/jenkins/agent/workspace/tiflash-build-common/tiflash") {
            sh """
            COMMIT_HASH_BASE=\$(git merge-base origin/${ghprbTargetBranch} HEAD)
            git diff --name-only "\$COMMIT_HASH_BASE" | { grep -E '.*\\.(cpp|h|hpp|cc|c)\$' || true; } > .git-diff-names
            rm -rf contrib
            rm -rf .git
            """
        }

        dir("/tmp/tiflash-data") {
            sh "tar --absolute-names -caf tiflash-src.tar.gz /home/jenkins/agent/workspace/tiflash-build-common/tiflash"
            stash "tiflash-ghpr-unit-tests-${BUILD_NUMBER}"
        }
    }

    dispatchRunEnv(toolchain, IDENTIFIER) {
        def cwd = pwd()
        def repo_path = "/home/jenkins/agent/workspace/tiflash-build-common/tiflash"
        def build_path = "/home/jenkins/agent/workspace/tiflash-build-common/build"
        def binary_path = "/tiflash"
        def parallelism = 12
        stage('Get Artifacts') {

            prepareArtifacts(built, false)
            sh """
            tar -xvaf tiflash.tar.gz
            ln -sf \$(realpath tiflash) /tiflash
            """

            dir("/tmp/tiflash-data") {
                unstash "tiflash-ghpr-unit-tests-${BUILD_NUMBER}"
                sh """
                mkdir -p ${repo_path}
                tar --absolute-names -xf tiflash-src.tar.gz
                chown -R 1000:1000 /home/jenkins/agent/workspace/tiflash-build-common/
                ls -lha ${repo_path}
                ln -sf ${repo_path}/tests /tests
                """
            }

            dir(repo_path) {
                sh """
                cp '${cwd}/source-patch.tar.xz' ./source-patch.tar.xz
                tar -xaf ./source-patch.tar.xz
                """
            }

            dir(build_path) {
                sh """
                cp '${cwd}/build-data.tar.xz' ./build-data.tar.xz
                tar -xaf ./build-data.tar.xz
                """
                if (toolchain == 'legacy' && coverage()) {
                    sh "touch -a -m \$(find . -name '*.gcno')"
                }
            }
        }
        stage('Run Tests') {
            timeout(time: 60, unit: 'MINUTES') {
                dir(repo_path) {
                    sh """
                    rm -rf /tmp-memfs/tiflash-tests
                    mkdir -p /tmp-memfs/tiflash-tests
                    export TIFLASH_TEMP_DIR=/tmp-memfs/tiflash-tests

                    mkdir -p /root/.cache
                    source /tests/docker/util.sh
                    export LLVM_PROFILE_FILE="/tiflash/profile/unit-test-%${parallelism}m.profraw"
                    show_env
                    ENV_VARS_PATH=/tests/docker/_env.sh OUTPUT_XML=true NPROC=${parallelism} /tests/run-gtest.sh
                    """
                }
            }
        }

        stage('Prepare Coverage Report') {
            if (!coverage()) {
                echo "skipped"
            }
            else if(toolchain == 'llvm') {
                dir(repo_path){
                    if (sh(returnStatus: true, script: "which lcov") != 0) {
                        echo "try to install lcov"
                        sh "rpm -i /home/jenkins/agent/dependency/lcov-1.15-1.noarch.rpm"
                    }
                    sh """
                    llvm-profdata merge -sparse /tiflash/profile/*.profraw -o /tiflash/profile/merged.profdata

                    export LD_LIBRARY_PATH=.
                    llvm-cov export \\
                        /tiflash/gtests_dbms /tiflash/gtests_libcommon /tiflash/gtests_libdaemon \\
                        --format=lcov \\
                        --instr-profile /tiflash/profile/merged.profdata \\
                        --ignore-filename-regex "/usr/include/.*" \\
                        --ignore-filename-regex "/usr/local/.*" \\
                        --ignore-filename-regex "/usr/lib/.*" \\
                        --ignore-filename-regex ".*/contrib/.*" \\
                        --ignore-filename-regex ".*/dbms/src/Debug/.*" \\
                        --ignore-filename-regex ".*/dbms/src/Client/.*" \\
                        > /tiflash/profile/lcov.info

                    mkdir -p /tiflash/report
                    genhtml /tiflash/profile/lcov.info -o /tiflash/report/ --ignore-errors source

                    pushd /tiflash
                        tar -czf coverage-report.tar.gz report
                        mv coverage-report.tar.gz ${cwd}
                    popd

                    SOURCE_DELTA=\$(cat .git-diff-names)
                    echo '### Coverage for changed files' > ${cwd}/diff-coverage
                    echo '```' >> ${cwd}/diff-coverage

                    if [[ -z \$SOURCE_DELTA ]]; then
                        echo 'no c/c++ source change detected' >> ${cwd}/diff-coverage
                    else
                        llvm-cov report /tiflash/gtests_dbms /tiflash/gtests_libcommon /tiflash/gtests_libdaemon -instr-profile /tiflash/profile/merged.profdata \$SOURCE_DELTA > "/tiflash/profile/diff-for-delta"
                        if [[ \$(wc -l "/tiflash/profile/diff-for-delta" | awk -e '{printf \$1;}') -gt 32 ]]; then
                            echo 'too many lines from llvm-cov, please refer to full report instead' >> ${cwd}/diff-coverage
                        else
                            cat /tiflash/profile/diff-for-delta >> ${cwd}/diff-coverage
                        fi
                    fi

                    echo '```' >> ${cwd}/diff-coverage
                    echo '' >> ${cwd}/diff-coverage
                    echo '### Coverage summary' >> ${cwd}/diff-coverage
                    echo '```' >> ${cwd}/diff-coverage
                    llvm-cov report \\
                        --summary-only \\
                        --show-region-summary=false \\
                        --show-branch-summary=false \\
                        --ignore-filename-regex "/usr/include/.*" \\
                        --ignore-filename-regex "/usr/local/.*" \\
                        --ignore-filename-regex "/usr/lib/.*" \\
                        --ignore-filename-regex ".*/contrib/.*" \\
                        --ignore-filename-regex ".*/dbms/src/Debug/.*" \\
                        --ignore-filename-regex ".*/dbms/src/Client/.*" \\
                        /tiflash/gtests_dbms /tiflash/gtests_libcommon /tiflash/gtests_libdaemon -instr-profile /tiflash/profile/merged.profdata | \\
                        grep -E "^(TOTAL|Filename)" | \\
                        cut -d" " -f2- | sed -e 's/^[[:space:]]*//' | sed -e 's/Missed\\ /Missed/g' | column -t >> ${cwd}/diff-coverage
                    echo '```' >> ${cwd}/diff-coverage
                    echo '' >> ${cwd}/diff-coverage
                    echo "[full coverage report](https://ci-internal.pingcap.net/job/tiflash-ghpr-unit-tests/${BUILD_NUMBER}/artifact/coverage-report.tar.gz) (for internal network access only)" >> ${cwd}/diff-coverage
                    """
                }
            } else {
                if (sh(returnStatus: true, script: "which gcovr") != 0) {
                    echo "try to install gcovr"
                    sh """
                    cp '/home/jenkins/agent/dependency/gcovr.tar' '/tmp/'
                    cd /tmp
                    tar xvf gcovr.tar && rm -rf gcovr.tar
                    ln -sf /tmp/gcovr/gcovr /usr/bin/gcovr
                    """
                }
                sh """
                mkdir -p /tiflash/profile/
                gcovr --xml -r ${repo_path} \\
                    --gcov-ignore-parse-errors \\
                    -e "/usr/include/*" \\
                    -e "/usr/local/*" \\
                    -e "/usr/lib/*" \\
                    -e "${repo_path}/contrib/*" \\
                    -e "${repo_path}/dbms/src/Debug/*" \\
                    -e "${repo_path}/dbms/src/Client/*" \\
                    --object-directory=${build_path} -o ${cwd}/tiflash_gcovr_coverage.xml -j ${parallelism} -s >${cwd}/diff-coverage
                """
            }
        }

        stage("Report Coverage") {
            if (coverage()) {
                ut_coverage_result = readFile(file: "diff-coverage").trim()
                withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                    sh """
                    set +x
                    /home/jenkins/agent/dependency/comment-pr \\
                        --token="\$TOKEN" \\
                        --owner=pingcap \\
                        --repo=tiflash \\
                        --number=${ghprbPullId} \\
                        --comment='${ut_coverage_result}'
                    set -x
                    """
                }
                if (toolchain == 'llvm') {
                    archiveArtifacts artifacts: "coverage-report.tar.gz"
                } else {
                    cobertura autoUpdateHealth: false, autoUpdateStability: false,
                        coberturaReportFile: "tiflash_gcovr_coverage.xml",
                        lineCoverageTargets: "${COVERAGE_RATE}, ${COVERAGE_RATE}, ${COVERAGE_RATE}",
                        maxNumberOfBuilds: 10, onlyStable: false, sourceEncoding: 'ASCII', zoomCoverageChart: false
                }
            } else {
                echo "skipped"
            }
        }
    }
}
    currentBuild.result = "SUCCESS"
} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println e
    currentBuild.result = "ABORTED"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
} finally {
    stage("upload-pipeline-data") {
        taskFinishTime = System.currentTimeMillis()
        build job: 'upload-pipelinerun-data',
            wait: false,
            parameters: [
                [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
                [$class: 'StringParameterValue', name: 'PIPELINE_RUN_URL', value: "${RUN_DISPLAY_URL}"],
                [$class: 'StringParameterValue', name: 'REPO', value: "pingcap/tiflash"],
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
