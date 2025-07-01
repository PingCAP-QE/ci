GROUP_SIZE = 2
// should be same as the configuration in tiflow/deployments/
TIDB_TEST_TAG="nightly"
ENGINE_TEST_TAG="dataflow:test"

labelBuild = "${JOB_NAME}-${BUILD_NUMBER}-build"
labelTest= "${JOB_NAME}-${BUILD_NUMBER}-test"
imageTag = "${JOB_NAME}-${ghprbPullId}"
dummyImageTag = "dummy"
specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
println "${specStr}"

/**
 * Partition the array.
 * @param array
 * @param size
 * @return Array partitions.
 */
static def partition(array, size) {
    def partitions = []
    int partitionCount = array.size() / size

    partitionCount.times { partitionNumber ->
        int start = partitionNumber * size
        int end = start + size - 1
        partitions << array[start..end]
    }

    if (array.size() % size) partitions << array[partitionCount * size..-1]
    return partitions
}

def list_pr_diff_files() {
    def list_pr_files_api_url = "https://api.github.com/repos/pingcap/tiflow/pulls/${ghprbPullId}/files"
    withCredentials([string(credentialsId: 'github-api-token-test-ci', variable: 'github_token')]) {
        response = httpRequest consoleLogResponseBody: false,
            contentType: 'APPLICATION_JSON', httpMode: 'GET',
            customHeaders:[[name:'Authorization', value:"token ${github_token}", maskValue: true]],
            url: list_pr_files_api_url, validResponseCodes: '200'

        def json = new groovy.json.JsonSlurper().parseText(response.content)

        echo "Status: ${response.status}"
        def files = []
        for (element in json) {
            files.add(element.filename)
        }

        println "pr diff files: ${files}"
        return files
    }
}

// if any file matches the pattern, return true
def pattern_match_any_file(pattern, files_list) {
    for (file in files_list) {
        if (file.matches(pattern)) {
            println "diff file matched: ${file}"
            return true
        }
    }

    return false
}

if (ghprbPullId != null && ghprbPullId != "" && !params.containsKey("triggered_by_upstream_pr_ci")) {
    def pr_diff_files = list_pr_diff_files()
    def pattern = /(^engine\/|^dm\/|^deployments\/engine\/|^go\.mod).*$/
    // if any diff files start with dm/ or engine/ , run the engine integration test
    def matched = pattern_match_any_file(pattern, pr_diff_files)
    if (matched) {
        echo "matched, some diff files full path start with engine/ or deployments/engine/ or go.mod, run the engine integration test"
    } else {
        echo "not matched, all files full path not start with engine/ or deployments/engine/ or go.mod, current pr not releate to dm, so skip the engine integration test"
        currentBuild.result = 'SUCCESS'
        return 0
    }
}


def prepare_binaries_and_images() {
    // prepare binaries
    def sync_diff_download_url = "${FILE_SERVER_URL}/download/test/tiflow/engine/ci/sync_diff.tar.gz"
    println "sync_diff_download_url: ${sync_diff_download_url}"
    sh """
    cd go/src/github.com/pingcap/tiflow && mkdir ./bin && cd ./bin
    curl -o sync_diff.tar.gz ${sync_diff_download_url}
    tar -xzvf ./sync_diff.tar.gz && rm -f ./sync_diff.tar.gz
    ls -l ./
    """

    // prepare images
    final defaultDependencyBranch = "master"
    def releaseBranchReg = /^release\-(\d+)\.(\d+)/      // example: release-6.1
    def hotfixBranchReg = /^release\-(\d+)\.(\d+)-(\d+)/ // example: release-6.1-20220719

    def dependencyBranch
    switch( ghprbTargetBranch ) {
        case ~releaseBranchReg:
            println "target branch is release branch, dependency use ${ghprbTargetBranch} branch to prepare binaries and images"
            dependencyBranch = ghprbTargetBranch
            break
        case ~hotfixBranchReg:
            def relBr = ghprbTargetBranch.replaceFirst(/-\d+$/, "")
            println "target branch is hotfix branch, dependency use ${relBr} branch to prepare binaries and images"
            dependencyBranch = relBr
            break
        default:
            dependencyBranch = defaultDependencyBranch
    }
    TIDB_CLUSTER_BRANCH = dependencyBranch
    // parse tidb cluster version
    def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m1) {
        TIDB_CLUSTER_BRANCH = "${m1[0][1]}"
    }
    m1 = null
    println "TIDB_CLUSTER_BRANCH=${TIDB_CLUSTER_BRANCH}"

    withCredentials([usernamePassword(credentialsId: '3929b35e-6d9a-423a-a3c3-9c584ff49ea0', usernameVariable: 'harborUser', passwordVariable: 'harborPassword')]) {
        sh """
        sleep 10
        docker version || true
        echo "${harborPassword}" | docker login -u ${harborUser} --password-stdin hub.pingcap.net
        """
    }

    sh """
    docker pull hub.pingcap.net/tiflow/minio:latest
    docker tag hub.pingcap.net/tiflow/minio:latest minio/minio:latest
    docker pull hub.pingcap.net/tiflow/minio:mc
    docker tag hub.pingcap.net/tiflow/minio:mc minio/mc:latest

    docker pull hub.pingcap.net/tiflow/mysql:5.7
    docker tag hub.pingcap.net/tiflow/mysql:5.7 mysql:5.7
    docker pull hub.pingcap.net/tiflow/mysql:8.0
    docker tag hub.pingcap.net/tiflow/mysql:8.0 mysql:8.0

    docker pull hub.pingcap.net/tiflow/etcd:latest
    docker tag hub.pingcap.net/tiflow/etcd:latest quay.io/coreos/etcd:latest

    docker pull hub.pingcap.net/qa/tidb:${TIDB_CLUSTER_BRANCH}
    docker tag hub.pingcap.net/qa/tidb:${TIDB_CLUSTER_BRANCH} pingcap/tidb:${TIDB_TEST_TAG}
    docker pull hub.pingcap.net/qa/tikv:${TIDB_CLUSTER_BRANCH}
    docker tag hub.pingcap.net/qa/tikv:${TIDB_CLUSTER_BRANCH} pingcap/tikv:${TIDB_TEST_TAG}
    docker pull hub.pingcap.net/qa/pd:${TIDB_CLUSTER_BRANCH}
    docker tag hub.pingcap.net/qa/pd:${TIDB_CLUSTER_BRANCH} pingcap/pd:${TIDB_TEST_TAG}

    docker pull hub.pingcap.net/tiflow/engine:${imageTag}
    docker tag hub.pingcap.net/tiflow/engine:${imageTag} ${ENGINE_TEST_TAG}

    docker images
    """
}

def doCheckout() {
    def codeCacheInFileserverUrl = "${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tiflow.tar.gz"
    def cacheExisted = sh(returnStatus: true, script: """
        if curl --output /dev/null --silent --head --fail ${codeCacheInFileserverUrl}; then exit 0; else exit 1; fi
        """)
    if (cacheExisted == 0) {
        println "get code from fileserver to reduce clone time"
        println "codeCacheInFileserverUrl=${codeCacheInFileserverUrl}"
        sh """
        curl -O ${codeCacheInFileserverUrl}
        tar -xzf src-tiflow.tar.gz --strip-components=1
        rm -f src-tiflow.tar.gz
        """
    } else {
        println "get code from github"
    }
    checkout(changelog: false, poll: false, scm: [
            $class           : "GitSCM",
            branches         : [
                    [name: ghprbActualCommit],
            ],
            userRemoteConfigs: [
                    [
                            url          : "https://github.com/pingcap/tiflow.git",
                            refspec      : specStr,
                            credentialsId: "github-sre-bot-ssh",
                    ]
            ],
            extensions       : [
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
                    [$class: 'CheckoutOption', timeout:30],
                    [$class: 'CloneOption', honorRefspec:true, timeout:30],
            ],
    ])
}


def run_with_pod(Closure body) {
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tiflow"
    def jnlp_docker_image = "jenkins/inbound-agent:3148.v532a_7e715ee3-10"
    podTemplate(label: labelBuild,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "hub.pingcap.net/jenkins/centos7_golang-1.18.5:latest", ttyEnabled: true,
                            resourceRequestCpu: '4000m', resourceRequestMemory: '6Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    )
            ],
            volumes: [
                            emptyDirVolume(mountPath: '/tmp', memory: false),
                    ],
    ) {
        node(labelBuild) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            timeout(time: 60, unit: 'MINUTES') {
                body()
            }
        }
    }
}

def run_with_test_pod(Closure body) {
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tiflow"
    def jnlp_docker_image = "jenkins/inbound-agent:3148.v532a_7e715ee3-10"
    podTemplate(label: labelTest,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(name: 'dockerd', image: 'docker:20.10.17-dind',
                                    envVars: [
                                            envVar(key: 'DOCKER_TLS_CERTDIR', value: ''),
                                            envVar(key: 'DOCKER_REGISTRY_MIRROR', value: 'https://registry-mirror.pingcap.net/"'),
                                    ], privileged: true,
                            resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi'),
                    containerTemplate(name: 'docker', image: 'hub.pingcap.net/tiflow/dind:alpine-docker',
                                    alwaysPullImage: true, envVars: [
                                            envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
                                    ], resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                                    ttyEnabled: true, command: 'cat'),
            ],
            volumes: [
                            emptyDirVolume(mountPath: '/tmp', memory: false),
                            emptyDirVolume(mountPath: '/var/lib/docker', memory: false)
                    ],
    ) {
        node(labelTest) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            timeout(time: 60, unit: 'MINUTES') {
                body()
            }
        }
    }
}

def archiveLogs(log_tar_name) {
    // handle logs peoperly
    sh """
    echo "archive logs..."
    ls /tmp/tiflow_engine_test/ || true
    tar -cvzf log-${log_tar_name}.tar.gz \$(find /tmp/tiflow_engine_test/ -type f -name "*.log") || true
    ls -alh log-${log_tar_name}.tar.gz || true
    """
    archiveArtifacts artifacts: "log-${log_tar_name}.tar.gz", caseSensitive: false, allowEmptyArchive: true
}

def run_test(cases) {
    try {
        unstash "tiflow-code"
        prepare_binaries_and_images()

        sh """
        cd go/src/github.com/pingcap/tiflow
        make engine_integration_test CASE="${cases}"
        """
    } catch (Exception e) {
        println(e.getMessage());
        throw e;
    } finally {
        archiveLogs(cases.replaceAll("\\s","-"))
    }
}


try {
run_with_pod {
    container("golang") {
        stage("checkout code") {
            dir("go/src/github.com/pingcap/tiflow") {
                echo "start to checkout..."
                doCheckout()
                sh "git checkout ${ghprbActualCommit}"
            }
            stash includes: "go/src/github.com/pingcap/tiflow/**", name: "tiflow-code", useDefaultExcludes: false
        }

        stage("build image") {
            run_with_test_pod{
                container("docker") {
                    echo "start to build image..."
                    unstash "tiflow-code"
                    withCredentials([usernamePassword(credentialsId: '3929b35e-6d9a-423a-a3c3-9c584ff49ea0', usernameVariable: 'harborUser', passwordVariable: 'harborPassword')]) {
                        sh """
                        sleep 10
                        docker version || true
                        docker-compose version || true
                        echo "${harborPassword}" | docker login -u ${harborUser} --password-stdin hub.pingcap.net
                        """
                    }
                    sh "go env | grep GOPROXY"
                    sh """
                    cd go/src/github.com/pingcap/tiflow
                    git config --global --add safe.directory /home/jenkins/agent/workspace/engine_ghpr_integration_test/go/src/github.com/pingcap/tiflow
                    git log | head
                    make tiflow tiflow-demo
                    touch ./bin/tiflow-chaos-case
                    make engine_image_from_local
                    docker tag ${ENGINE_TEST_TAG} hub.pingcap.net/tiflow/engine:${imageTag}
                    docker push hub.pingcap.net/tiflow/engine:${imageTag}
                    """
                }
            }
        }

        stage("test") {
            // Run integration tests in groups.
            def tests = [:]
            dir("go/src/github.com/pingcap/tiflow/engine/test/integration_tests") {
                sh """
                pwd
                ls -alh .
                """
                def cases_name = sh(
                        script: 'find . -maxdepth 2 -mindepth 2 -name \'run.sh\' | awk -F/ \'{print $2}\'',
                        returnStdout: true
                ).trim().split()
                println "${cases_name}"

                def step_cases = []
                def cases_namesList = partition(cases_name, GROUP_SIZE)
                TOTAL_COUNT = cases_namesList.size()
                cases_namesList.each { case_names ->
                    step_cases.add(case_names)
                }
                step_cases.eachWithIndex { case_names, index ->
                    def step_name = "step_${index}"
                    tests["integration test ${step_name}"] = {
                        run_with_test_pod{
                            container("docker") {
                                run_test(case_names.join(" "))
                            }
                        }
                    }
                }
            }
            parallel tests
        }

        stage("remove image") {
            run_with_test_pod{
                container("docker") {
                    echo "start to remove image..."
                    withCredentials([usernamePassword(credentialsId: '3929b35e-6d9a-423a-a3c3-9c584ff49ea0', usernameVariable: 'harborUser', passwordVariable: 'harborPassword')]) {
                        sh """
                        sleep 10
                        docker version || true
                        docker-compose version || true
                        echo "${harborPassword}" | docker login -u ${harborUser} --password-stdin hub.pingcap.net || true
                        """
                    }
                    sh """
                    docker pull hub.pingcap.net/tiflow/engine:${dummyImageTag} || true
                    docker tag hub.pingcap.net/tiflow/engine:${dummyImageTag} hub.pingcap.net/tiflow/engine:${imageTag} || true
                    docker push hub.pingcap.net/tiflow/engine:${imageTag} || true
                    """
                }
            }
        }
    }
}
   currentBuild.result = "SUCCESS"
} catch (Exception e) {
    println "Exception: ${e}"
    currentBuild.result = 'FAILURE'
} finally {
    println "Finally"
}
