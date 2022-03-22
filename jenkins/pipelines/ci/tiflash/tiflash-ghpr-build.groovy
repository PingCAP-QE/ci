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


def parameters = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "CMAKE_BUILD_TYPE", value: "Debug"),
        string(name: "TARGET_BRANCH", value: ghprbTargetBranch),
        string(name: "TARGET_PULL_REQUEST", value: ghprbPullId),
        string(name: "TARGET_COMMIT_HASH", value: ghprbActualCommit),
        [$class: 'BooleanParameterValue', name: 'BUILD_TIFLASH', value: true],
        [$class: 'BooleanParameterValue', name: 'BUILD_PAGE_TOOLS', value: false],
        [$class: 'BooleanParameterValue', name: 'BUILD_TESTS', value: false],
        [$class: 'BooleanParameterValue', name: 'ENABLE_CCACHE', value: true],
        [$class: 'BooleanParameterValue', name: 'ENABLE_PROXY_CACHE', value: true],
        [$class: 'BooleanParameterValue', name: 'UPDATE_CCACHE', value: false],
        [$class: 'BooleanParameterValue', name: 'UPDATE_PROXY_CACHE', value: false],
        [$class: 'BooleanParameterValue', name: 'ENABLE_STATIC_ANALYSIS', value: true],
        [$class: 'BooleanParameterValue', name: 'ENABLE_FORMAT_CHECK', value: true],
        [$class: 'BooleanParameterValue', name: 'ENABLE_COVERAGE', value: false],
        [$class: 'BooleanParameterValue', name: 'PUSH_MESSAGE', value: false],
        [$class: 'BooleanParameterValue', name: 'DEBUG_WITHOUT_DEBUG_INFO', value: true],
        [$class: 'BooleanParameterValue', name: 'ARCHIVE_ARTIFACTS', value: true],
        [$class: 'BooleanParameterValue', name: 'ENABLE_FAILPOINTS', value: true],
    ]

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

stage("Sync Status") {
    node {
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
    }
}
