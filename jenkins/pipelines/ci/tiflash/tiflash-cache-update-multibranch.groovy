def coverage() {
    if (env.BRANCH_NAME.contains("release-5.2") 
     || env.BRANCH_NAME.contains("release-5.1")
     || env.BRANCH_NAME.contains("release-5.0") 
     || env.BRANCH_NAME.contains("release-4.0") 
     || env.BRANCH_NAME.contains("release-3.0")) {
        return false
    }
    return true
}

def page_tools() {
    if (env.BRANCH_NAME.contains("release-5.1")
     || env.BRANCH_NAME.contains("release-5.0") 
     || env.BRANCH_NAME.contains("release-4.0") 
     || env.BRANCH_NAME.contains("release-3.0")) {
        return false
    }
    return true
}

def build_parameters = [
    string(name: "ARCH", value: "amd64"),
    string(name: "OS", value: "linux"),
    string(name: "CMAKE_BUILD_TYPE", value: "Debug"),
    string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
    string(name: "TARGET_PULL_REQUEST", value: ""),
    string(name: "TARGET_COMMIT_HASH", value: ""),
    string(name: "EXTRA_SUFFIX", value: "-build"),
    [$class: 'BooleanParameterValue', name: 'BUILD_TIFLASH', value: true],
    [$class: 'BooleanParameterValue', name: 'BUILD_PAGE_TOOLS', value: false],
    [$class: 'BooleanParameterValue', name: 'BUILD_TESTS', value: false],
    [$class: 'BooleanParameterValue', name: 'ENABLE_CCACHE', value: true],
    [$class: 'BooleanParameterValue', name: 'ENABLE_PROXY_CACHE', value: true],
    [$class: 'BooleanParameterValue', name: 'UPDATE_CCACHE', value: true],
    [$class: 'BooleanParameterValue', name: 'UPDATE_PROXY_CACHE', value: true],
    [$class: 'BooleanParameterValue', name: 'ENABLE_STATIC_ANALYSIS', value: false],
    [$class: 'BooleanParameterValue', name: 'ENABLE_FORMAT_CHECK', value: false],
    [$class: 'BooleanParameterValue', name: 'ENABLE_COVERAGE', value: false],
    [$class: 'BooleanParameterValue', name: 'PUSH_MESSAGE', value: false],
    [$class: 'BooleanParameterValue', name: 'DEBUG_WITHOUT_DEBUG_INFO', value: true],
    [$class: 'BooleanParameterValue', name: 'ARCHIVE_ARTIFACTS', value: false],
    [$class: 'BooleanParameterValue', name: 'ENABLE_FAILPOINTS', value: true],
]

// tiflash-build-common will automatically detect if page tools can be built
def ut_parameters = [
    string(name: "ARCH", value: "amd64"),
    string(name: "OS", value: "linux"),
    string(name: "CMAKE_BUILD_TYPE", value: "Debug"),
    string(name: "TARGET_BRANCH", value: env.BRANCH_NAME),
    string(name: "TARGET_PULL_REQUEST", value: ""),
    string(name: "TARGET_COMMIT_HASH", value: ""),
    string(name: "EXTRA_SUFFIX", value: "-ut"),
    [$class: 'BooleanParameterValue', name: 'BUILD_TIFLASH', value: false],
    [$class: 'BooleanParameterValue', name: 'BUILD_PAGE_TOOLS', value: page_tools()],
    [$class: 'BooleanParameterValue', name: 'BUILD_TESTS', value: true],
    [$class: 'BooleanParameterValue', name: 'ENABLE_CCACHE', value: true],
    [$class: 'BooleanParameterValue', name: 'ENABLE_PROXY_CACHE', value: true],
    [$class: 'BooleanParameterValue', name: 'UPDATE_CCACHE', value: true],
    [$class: 'BooleanParameterValue', name: 'UPDATE_PROXY_CACHE', value: false],
    [$class: 'BooleanParameterValue', name: 'ENABLE_STATIC_ANALYSIS', value: false],
    [$class: 'BooleanParameterValue', name: 'ENABLE_FORMAT_CHECK', value: false],
    [$class: 'BooleanParameterValue', name: 'ENABLE_COVERAGE', value: coverage()],
    [$class: 'BooleanParameterValue', name: 'PUSH_MESSAGE', value: false],
    [$class: 'BooleanParameterValue', name: 'DEBUG_WITHOUT_DEBUG_INFO', value: true],
    [$class: 'BooleanParameterValue', name: 'ARCHIVE_ARTIFACTS', value: false],
    [$class: 'BooleanParameterValue', name: 'ENABLE_FAILPOINTS', value: true],
]

stage('Update Cache') {
    parallel(
        "Update Build Cache": {build job: "tiflash-build-common",
            wait: true,
            propagate: true,
            parameters: build_parameters
        },
        "Update UT Cache": {build job: "tiflash-build-common",
            wait: true,
            propagate: true,
            parameters: ut_parameters
        },
    )
}