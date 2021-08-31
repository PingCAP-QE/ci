catchError {
    def suites = params.SUITE_FILES.split(/\n+/).collect { it.trim() }.findAll { it && !it.startsWith("#") }
    def c = Math.min(Math.max(1, params.CONCURRENCY.toInteger()), suites.size())
    def ok = true
    def tasks = [:]

    for (k = 0; k < c; k++) {
        def g = suites.withIndex().findAll { it[1] % c == k }.collect { it[0] }
        tasks["Group $k"] = {
            g.each {
                echo("# $it")
                try {
                    build(job: "utf-py-test", parameters: [
                        string(name: "SUITE_FILE", value: it),
                        string(name: 'IMAGE', value: params.IMAGE),
                        string(name: "EXTRA_ARGS", value: "--annotation jenkins.trigger=${BUILD_URL} ${params.EXTRA_ARGS}".trim()),
                        string(name: "TIMEOUT", value: params.TIMEOUT),
                        booleanParam(name: "REPORT", value: params.REPORT),
                    ])
                } catch (e) {
                    echo("Error: $e")
                    ok = false
                }
            }
        }
    }

    stage('Test') { parallel(tasks) }

    stage('Check') { assert ok }

}
