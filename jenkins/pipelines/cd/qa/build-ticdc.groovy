properties([
        parameters([
                string(name: 'FORK', defaultValue: 'pingcap', description: '', trim: true),
                string(name: 'BRANCH', defaultValue: 'master', description: '', trim: true),
                string(name: 'TIUP_MIRRORS', defaultValue: 'http://172.16.4.71:31888', description: '', trim: true),
                booleanParam(name: 'TIUP_PUBLISH', defaultValue: false, description: ''),
                string(name: 'REFSPEC', defaultValue: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*', description: '', trim: true),
                string(name: 'VERSION', defaultValue: '', description: '', trim: true),
        ])
])

def main() {
    def archiveURL = ""
    def tag = params.BRANCH.replaceAll("/", "-")
    if (params.FORK != "pingcap") {
        tag = "${tag}-${params.FORK}"
    }
    if (params.VERSION != "") {
        tag = "$params.VERSION"
    }
    def release = 1
    if (params.FORK == "pingcap") {
        def filepath = "http://fileserver.pingcap.net/download/builds/pingcap/ticdc/optimization/${params.BRANCH}/centos7/ticdc.tar.gz"
        release = sh(returnStatus: true, script: """
                    curl --output /dev/null --silent --head --fail ${filepath}
                    """)
        if (release == 0) {
            sh "curl ${filepath}| tar xz"
        }
    }
    if (release != 0) {
        stage("Checkout") {
            checkout(changelog: false, poll: false, scm: [
                    $class           : "GitSCM",
                    branches         : [[name: params.BRANCH]],
                    userRemoteConfigs: [[url: "https://github.com/${params.FORK}/ticdc.git", refspec: params.REFSPEC]],
                    extensions       : [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
            ])
        }
    }
    stage("Build") {
        container("golang") {
            if (release != 0) {
                sh("make")
            }
            retry(3) {
                sh("""
                cat > Dockerfile << __EOF__
FROM hub.pingcap.net/mirrors/centos:7

COPY cdc /bin/cdc

RUN ln -sf /bin/cdc /cdc

ENTRYPOINT ["/cdc"]

# hub.pingcap.net/qa/ticdc
__EOF__
                curl -sL -o tici.sh http://fileserver.pingcap.net/download/pingcap/qa/scripts/tici.sh
                chmod +x tici.sh
                """)
                archiveURL = sh(script: "./tici.sh publish $params.TIUP_PUBLISH $params.TIUP_MIRRORS cdc pingcap/qa/archives cdc ${tag} Dockerfile -C bin .", returnStdout: true).trim()
            }
            echo(archiveURL)
        }
    }
    stage("Image") {
        build(job: "image-build", parameters: [
                string(name: "CONTEXT_URL", value: archiveURL),
                string(name: "DESTINATION", value: "hub.pingcap.net/qa/ticdc:${tag}"),
        ])
    }
}

def run(label, image, Closure main) {
    podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 60, containers: [
            containerTemplate(name: 'golang', image: image, alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
    ]) { node(label) { dir("ticdc") { main() } } }
}

catchError {
    run('build-go1164', 'hub.pingcap.net/pingcap/centos7_golang-1.16') { main() }
}
