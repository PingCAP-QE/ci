def main(image) {
    stage("Test") {
        echo("Run ${params.SUITE_FILE}")
        container("suite") {
            sh("cp -r /automated-tests automated-tests")
            dir('automated-tests') {
                if (params.MANIFEST) {
                    sh("chown -R 1000:1000 ./")
                    writeFile(file: params.SUITE_FILE, text: params.MANIFEST)
                }

                def args = params.EXTRA_ARGS
                args += " --annotation suite.image=$image"
                args += " --annotation jenkins.build=$BUILD_URL"
                args += " --annotation jenkins.started_at=${new Date(currentBuild.startTimeInMillis).format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")}"
                if (params.REPORT) { args += " --report-to http://\$AUTH@${env.RESULT_STORE_ENDPOINT}" }

                def runSuite = "python -m cases.cli info -i ${params.SUITE_FILE} | jq -r '${params.SELECTOR}' | xargs python -m cases.cli run -i ${params.SUITE_FILE} $args"
                if (params.CASES) { runSuite = "python -m cases.cli run -i ${params.SUITE_FILE} $args ${params.CASES}" }

                def run = { sh(runSuite) }
                if (params.TIMEOUT) {
                    def f = run
                    run = { timeout(params.TIMEOUT.toInteger()) { f() } }
                }
                if (params.REPORT) {
                    def f = run
                    run = { withCredentials([usernameColonPassword(credentialsId: "result-store", variable: "AUTH")]) { f() } }
                }

                try { run() } finally { archiveArtifacts(artifacts: 'logs/*.log', allowEmptyArchive: true) }
            }
        }
    }
}

def run(label, image, Closure body) {
    podTemplate(name: label, label: label, instanceCap: 5, serviceAccount: 'utf-runner', containers: [
        containerTemplate(name: 'suite', image: image, alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
    ]) { node(label) { body() } }
}

catchError {
    run("utf-python-test", params.IMAGE) { main(params.IMAGE) }
}
