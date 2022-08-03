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
        for (i in ['release-4.0', 'release-5.0', 'release-5.1', 'release-5.2']) {
                if (ghprbTargetBranch.contains(i)) {
                        return true
                }
        }
        return false
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
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-tiflash"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "hub.pingcap.net/jenkins/centos7_golang-1.18.5:latest", ttyEnabled: true,
                        resourceRequestCpu: '200m', resourceRequestMemory: '1Gi',
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
            body()
        }
    }
}


run_with_pod {
        stage('Build') {
                def built = build(
                                job: "tiflash-build-common",
                                wait: true,
                                propagate: false,
                                parameters: parameters
                        )
                echo "built at: https://ci.pingcap.net/blue/organizations/jenkins/tiflash-build-common/detail/tiflash-build-common/${built.number}/pipeline"
                if (built.getResult() != 'SUCCESS') {
                        error "build failed"
                }
        }
}

