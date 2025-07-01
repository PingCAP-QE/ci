if (params.containsKey("release_test") && params.get("release_test") == "true") {
    echo "this build is triggered by qa for release testing"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tiflash_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def runTest(label, name, path, tidb_branch) {
    podTemplate(
        name: label,
        label: label,
        cloud: "kubernetes-ksyun",
        namespace: "jenkins-tiflash",
        idleMinutes: 0,
        nodeSelector: "kubernetes.io/arch=amd64",
        instanceCap: 15,
        containers: [
            containerTemplate(name: 'dockerd', image: 'docker:20.10.24-dind', privileged: true,
                    resourceRequestCpu: '5000m', resourceRequestMemory: '10Gi',
                    resourceLimitCpu: '16000m', resourceLimitMemory: '32Gi',
                    envVars: [
                            envVar(key: 'DOCKER_TLS_CERTDIR', value: ''),
                            envVar(key: 'DOCKER_REGISTRY_MIRROR', value: 'https://registry-mirror.pingcap.net/'),
                    ]),
            containerTemplate(name: 'docker', image: 'hub.pingcap.net/jenkins/docker:build-essential-java',
                    alwaysPullImage: true, envVars: [envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375')], ttyEnabled: true, command: 'cat')],
        volumes: [
            // TODO use s3 cache instead of nfs
            nfsVolume(mountPath: '/home/jenkins/agent/dependency', serverAddress: "${NFS_SERVER_ADDRESS}",
                    serverPath: '/data/nvme1n1/nfs/tiflash/dependency', readOnly: true),
            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: "${NFS_SERVER_ADDRESS}",
                    serverPath: '/data/nvme1n1/nfs/git', readOnly: true)])
    {
        node(label) {
            stage('Unstash') {
                unstash 'code-and-artifacts'
            }
            stage('Run') {
                dir(path) {
                    timeout(time: 60, unit: 'MINUTES') {
                        container("docker") {
                            try {
                                echo "path: ${pwd()}"
                                sh """
                                echo "wait docker daemon ready ..."
                                while true; do
                                    if docker version &> /dev/null; then
                                        echo "Docker daemon ready."
                                        break
                                    else
                                        echo "Docker daemon not ready, wait..."
                                        sleep 2
                                    fi
                                done
                                docker version
                                """
                                sh "TAG=${ghprbActualCommit} BRANCH=${tidb_branch} ./run.sh"
                            } catch (e) {
                                sh label: "debug fail", script: """
                                docker ps -a
                                mv log ${name}-log
                                find ${name}-log -name '*.log' | xargs tail -n 500
                                """
                                sh label: "archive logs", script: """
                                chown -R 1000:1000 ./
                                find ${name}-log -type f -name "*.log" -exec tar -czvf ${name}-logs.tar.gz {} +
                                chown -R 1000:1000 ./
                                ls -alh ${name}-logs.tar.gz
                                """
                                archiveArtifacts(artifacts: "${name}-logs.tar.gz", allowEmptyArchive: true)
                                throw e
                            }
                        }
                    }
                }
            }
        }
    }
}

def checkoutTiFlash() {
    container('golang') {
        stage('Checkout') {
            def cache_path = "/home/jenkins/agent/ci-cached-code-daily/src-tics.tar.gz"
            if (fileExists(cache_path)) {
                sh label: "Get code from nfs to reduce clone time", script: """
                cp -R ${cache_path}  ./
                tar -xzf ${cache_path} --strip-components=1
                rm -f src-tics.tar.gz
                chown -R 1000:1000 ./
                """
            }

            def refspec = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"

            if (!ghprbPullId) {
                refspec = "+refs/heads/*:refs/remotes/origin/*"
            }

            checkout(changelog: false, poll: false,
                scm: [
                    $class                           : "GitSCM",
                    branches                         : [[name: ghprbActualCommit]],
                userRemoteConfigs : [
                    [
                        url           : "git@github.com:pingcap/tiflash.git",
                        refspec       : refspec,
                        credentialsId : "github-sre-bot-ssh",
                    ]
                ],
                extensions : [
                    [$class             : 'SubmoduleOption',
                    disableSubmodules   : true,
                    parentCredentials   : true,
                    recursiveSubmodules : true,
                    trackingSubmodules  : false,
                    reference           : ''],
                    [$class: 'PruneStaleBranch'],
                            [$class: 'CleanBeforeCheckout'],
                    ],
                    doGenerateSubmoduleConfigurations: false,
                ])
        }
        dir('tests/.build') {
            stage('Get Artifacts') {
                copyArtifacts(
                    projectName: 'tiflash-build-common',
                    parameters: "TARGET_BRANCH=${ghprbTargetBranch},TARGET_PULL_REQUEST=${ghprbPullId},TARGET_COMMIT_HASH=${ghprbActualCommit},BUILD_TIFLASH=true",
                    selector: lastSuccessful(),
                    optional: false)
                sh "tar -zxvf tiflash.tar.gz"
            }
        }

        stage('Stash') {
            sh """
            rm -rf .git
            rm -rf contrib
            """
            stash name: "code-and-artifacts", useDefaultExcludes: true
        }
    }
}

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}-for-it"
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
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false),
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: "${NFS_SERVER_ADDRESS}",
                        serverPath: '/data/nvme1n1/nfs/git', readOnly: true)
            ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""

try {
run_with_pod {
    container("golang") {
        def toolchain = null
        def identifier = "tiflash-integration-test-${ghprbTargetBranch}-${ghprbPullId}"
        def repo_path = "/home/jenkins/agent/workspace/tiflash-build-common/tiflash"
        def tidb_branch = ({
            def m = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
            if (m) {
                return "${m.group(1)}"
            }
            return ghprbTargetBranch ?: 'master'
        }).call()
        if (ghprbTargetBranch in ["planner_refactory"]) {
            tidb_branch = "master"
        }

        stage('Wait for Build') {
            def api = "https://ci.pingcap.net/job/tiflash-build-common/api/xml?tree=allBuilds[result,building,actions[parameters[name,value]]]&xpath=(//allBuild[action[parameter[name=%22TARGET_COMMIT_HASH%22%20and%20value=%22${ghprbActualCommit}%22]%20and%20parameter[name=%22BUILD_TIFLASH%22%20and%20value=%22true%22]]])[1]"
            echo "poll status from: ${api}"
            waitUntil(quiet: true) {
                def response = httpRequest(url: api, validResponseCodes: "100:399,404", quiet: true)
                def content = response.getContent()
                if (content.contains('<result>FAILURE</result>')) {
                    error "build failure"
                }
                if (!content.contains('<building>false</building>') || !content.contains('<result>SUCCESS</result>')) {
                    return false
                }
                return true
            }
            copyArtifacts(
                projectName: 'tiflash-build-common',
                parameters: "TARGET_BRANCH=${ghprbTargetBranch},TARGET_PULL_REQUEST=${ghprbPullId},TARGET_COMMIT_HASH=${ghprbActualCommit},BUILD_TIFLASH=true",
                filter: 'toolchain',
                selector: lastSuccessful(),
                optional: false
            )
            toolchain = readFile(file: 'toolchain').trim()
            echo "Built with ${toolchain}"
        }

        checkoutTiFlash()

        parallel (
            "tidb ci test": {
                def name = "tidb-ci-test"
                runTest("${identifier}-${name}-${BUILD_NUMBER}", name, "tests/tidb-ci", tidb_branch)
            },
            "delta merge test": {
                def name = "delta-merge-test"
                runTest("${identifier}-${name}-${BUILD_NUMBER}", name, "tests/delta-merge-test", tidb_branch)
            },
            "fullstack test": {
                def name = "fullstack-test"
                runTest("${identifier}-${name}-${BUILD_NUMBER}", name, "tests/fullstack-test", tidb_branch)
            },
            "fullstack test2": {
                def name = "fullstack-test2"
                runTest("${identifier}-${name}-${BUILD_NUMBER}", name, "tests/fullstack-test2", tidb_branch)
            }
        )
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
