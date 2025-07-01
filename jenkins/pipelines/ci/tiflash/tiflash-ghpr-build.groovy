// properties([
//         parameters([
//                 string(name: 'ghprbActualCommit', defaultValue: '', description: '', trim: true),
//                 string(name: 'ghprbPullId', defaultValue: '', description: '', trim: true),
//                 string(name: 'ghprbPullTitle', defaultValue: '', description: '', trim: true),
//                 string(name: 'ghprbPullLink', defaultValue: '', description: '', trim: true),
//                 string(name: 'ghprbPullDescription', defaultValue: '', description: '', trim: true),
//                 string(name: 'ghprbCommentBody', defaultValue: '', description: '', trim: true),
//                 string(name: 'ghprbTargetBranch', defaultValue: 'master', description: '', trim: true),
//                 string(name: 'tiflashTag', defaultValue: 'master', description: '', trim: true),
//         ])
// ])

def need_tests() {
    for (i in ['release-4.0']) {
        if (ghprbTargetBranch.contains(i)) {
            return true
        }
    }
    return false
}

def disable_lint_or_format() {
    if (ghprbTargetBranch == "master") {
        return false
    }
    return true
}

def parameters = [
    string(name: "ARCH", value: "amd64"),
    string(name: "OS", value: "linux"),
    string(name: "CMAKE_BUILD_TYPE", value: "Debug"),
    string(name: "TARGET_BRANCH", value: ghprbTargetBranch),
    string(name: "TARGET_PULL_REQUEST", value: ghprbPullId),
    string(name: "TARGET_COMMIT_HASH", value: ghprbActualCommit),
    [$class: 'BooleanParameterValue', name: 'BUILD_TIFLASH', value: true],
    [$class: 'BooleanParameterValue', name: 'BUILD_PAGE_TOOLS', value: false],
    [$class: 'BooleanParameterValue', name: 'BUILD_TESTS', value: need_tests()],
    [$class: 'BooleanParameterValue', name: 'ENABLE_CCACHE', value: true],
    [$class: 'BooleanParameterValue', name: 'ENABLE_PROXY_CACHE', value: true],
    [$class: 'BooleanParameterValue', name: 'UPDATE_CCACHE', value: false],
    [$class: 'BooleanParameterValue', name: 'UPDATE_PROXY_CACHE', value: false],
    [$class: 'BooleanParameterValue', name: 'ENABLE_STATIC_ANALYSIS', value: !disable_lint_or_format()],
    [$class: 'BooleanParameterValue', name: 'ENABLE_FORMAT_CHECK', value: !disable_lint_or_format()],
    [$class: 'BooleanParameterValue', name: 'ENABLE_COVERAGE', value: false],
    [$class: 'BooleanParameterValue', name: 'PUSH_MESSAGE', value: false],
    [$class: 'BooleanParameterValue', name: 'DEBUG_WITHOUT_DEBUG_INFO', value: true],
    [$class: 'BooleanParameterValue', name: 'ARCHIVE_ARTIFACTS', value: true],
    [$class: 'BooleanParameterValue', name: 'ENABLE_FAILPOINTS', value: true],
]


def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
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
            emptyDirVolume(mountPath: '/home/jenkins', memory: false)
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
        stage('Build') {
            def built = build(
                job: "tiflash-build-common",
                wait: true,
                propagate: false,
                parameters: parameters
            )
            if (built.getResult() != 'SUCCESS') {
                error "Build failed, see: https://ci.pingcap.net/blue/organizations/jenkins/tiflash-build-common/detail/tiflash-build-common/${built.number}/pipeline"
            } else {
                echo "Built at: https://ci.pingcap.net/blue/organizations/jenkins/tiflash-build-common/detail/tiflash-build-common/${built.number}/pipeline"
            }
        }
    }
    currentBuild.result = "SUCCESS"
} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    currentBuild.result = "ABORTED"
    echo "${e}"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
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
