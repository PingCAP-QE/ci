
def asan_parameters = [
        [$class: 'BooleanParameterValue', name: 'UPDATE_CCACHE', value: false],
        string(name: "SANITIZER", value: "ASan"),
        string(name: "TARGET_PULL_REQUEST", value: params.ghprbPullId),
        string(name: "TARGET_COMMIT_HASH", value: params.ghprbActualCommit),
    ]

def tsan_parameters = [
        [$class: 'BooleanParameterValue', name: 'UPDATE_CCACHE', value: false],
        string(name: "SANITIZER", value: "TSan"),
        string(name: "TARGET_PULL_REQUEST", value: params.ghprbPullId),
        string(name: "TARGET_COMMIT_HASH", value: params.ghprbActualCommit),
    ]

stage('Sanitizer') {
    parallel(
        "ASan" : {
            if (ghprbCommentBody.toLowerCase().contains("asan")) {
                build(
                        job: "tiflash-sanitizer-daily",
                        wait: true,
                        propagate: true,
                        parameters: asan_parameters
                    )
            }
        },
        "TSan" : {
            if (ghprbCommentBody.toLowerCase().contains("tsan")) {
                build(
                        job: "tiflash-sanitizer-daily",
                        wait: true,
                        propagate: true,
                        parameters: tsan_parameters
                    )
            }
        }
    )
}
