def runUTFGo(args) {

    def ok = true

    def run = { suite ->
        stage("Run $suite") {
            try {
                build(job: 'utf-go-build', parameters: [
                    string(name: "SUITE", value: suite),
                    string(name: "TAG", value: "alpha1"),
                ])
                build(job: 'utf-go-test', parameters: [
                    string(name: 'SUITE', value: suite),
                    string(name: "TAG", value: "alpha1"),
                    string(name: 'EXTRA_ARGS', value: args),
                    booleanParam(name: 'REPORT', value: true),
                ])
            } catch (e) {
                println("Error: $e")
                ok = false
            }
        }
    }

    parallel(
        'Group 1': {
            run('clustered_index')
        },
        'Group 2': {
            run('ticdc')
            run('rowformat')
        },
        'Group 3': {
            run('readpool')
            run('regression')
        },
    )

    assert ok
}

def runUTFPy(args) {
    build(job: 'utf-py-build')
    build(job: 'utf-py-test-batch', parameters: [
        string(name: 'EXTRA_ARGS', value: args),
        string(name: 'CONCURRENCY', value: '3'),
        booleanParam(name: 'REPORT', value: true),
    ])
}

catchError {
    def args = params.EXTRA_ARGS
    args += " --annotation jenkins.trigger=$BUILD_URL"
    args += " --annotation utf.daily_test=${new Date(currentBuild.startTimeInMillis).format("yyyy-MM-dd")}"
    parallel(
        'Run UTF Go': { runUTFGo(args) },
        'Run UTF Py': { runUTFPy(args) },
    )
}
