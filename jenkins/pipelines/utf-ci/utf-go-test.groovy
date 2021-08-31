def main(image, targetName) {
    stage("Test") {
        echo("Run ${targetName} suite")
        container("suite") {
            if (params.MANIFEST) {
                writeFile(file: params.MANIFEST_FILE, text: params.MANIFEST)
            } else {
                sh("cp /*.libsonnet ./ | true")
                sh("cp /*.jsonnet ./")
            }

            def args = params.EXTRA_ARGS
            args += " --annotation suite.image=$image"
            args += " --annotation jenkins.build=$BUILD_URL"
            args += " --annotation jenkins.started_at=${new Date(currentBuild.startTimeInMillis).format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")}"
            if (params.REPORT) { args += " --report-to http://\$AUTH@${env.RESULT_STORE_ENDPOINT}" }

            def runEnv = 'export UTF_RESOWNER="{\\"apiVersion\\":\\"v1\\",\\"kind\\":\\"Pod\\",\\"name\\":\\"$AGENT_NAME\\",\\"uid\\":\\"$AGENT_UID\\"}"\n'
            def runSuite = "/${targetName} info -i ${params.MANIFEST_FILE} | jq -r '${params.SELECTOR}' | xargs /${targetName} run -i ${params.MANIFEST_FILE} $args"
            if (params.CASES) { runSuite = "/${targetName} run -i ${params.MANIFEST_FILE} $args ${params.CASES}" }

            def run = { sh(runEnv + runSuite) }
            if (params.TIMEOUT) {
                def f = run
                run = { timeout(params.TIMEOUT.toInteger()) { f() } }
            }
            if (params.REPORT) {
                def f = run
                run = { withCredentials([usernameColonPassword(credentialsId: "result-store", variable: "AUTH")]) { f() } }
            }

            run()
        }
    }
}

def run(label, image, Closure body) {
    podTemplate(name: label, label: label, instanceCap: 5, serviceAccount: 'utf-runner', containers: [
        containerTemplate(name: 'suite', image: image, alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
    ], yamlMergeStrategy: merge(), yaml: """
spec:
  containers:
  - name: suite
    env:
    - name: AGENT_NAME
      valueFrom:
        fieldRef:
          apiVersion: v1
          fieldPath: metadata.name
    - name: AGENT_UID
      valueFrom:
        fieldRef:
          apiVersion: v1
          fieldPath: metadata.uid
""") { node(label) { dir(params.SUITE) { body() } } }
}

catchError {
    def targetName = params.SUITE.replaceAll('/', '-')
    def image = params.IMAGE
    if (image == "") {
        image = "hub-new.pingcap.net/qa/utf-go-${targetName}"
        if (params.TAG) { image += ":" + params.TAG }
    }

    run("utf-go-test-${targetName}-${BUILD_NUMBER}", image) { main(image, targetName) }
}