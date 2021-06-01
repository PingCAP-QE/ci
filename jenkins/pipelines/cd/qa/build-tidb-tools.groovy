properties([
        parameters([
                string(name: 'FORK', defaultValue: 'pingcap', description: '', trim: true),
                string(name: 'BRANCH', defaultValue: 'master', description: '', trim: true),
                string(name: 'REFSPEC', defaultValue: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*', description: '', trim: true),
        ])
])

def main() {
    def archiveURL = ""
    def tag = params.BRANCH.replaceAll("/", "-")
    if (params.FORK != "pingcap") {
        tag = "${tag}-${params.FORK}"
    }
    def release = 1
    if (params.FORK == "pingcap") {
        def filepath = "http://fileserver.pingcap.net/download/builds/pingcap/tidb-tools/optimization/${params.BRANCH}/centos7/tidb-tools.tar.gz"
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
                    userRemoteConfigs: [[url: "https://github.com/${params.FORK}/tidb-tools.git", refspec: params.REFSPEC]],
                    extensions       : [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
            ])
        }
    }
    stage("Build") {
        container("golang") {
            if (release != 0) {
                sh "make build"
            }
            sh("""
            curl -sL -o tici.sh http://fileserver.pingcap.net/download/pingcap/qa/scripts/tici.sh
            """)
            archiveURL = sh(script: "./tici.sh publish pingcap/qa/archives tidb_tools ${tag} bin/", returnStdout: true).trim()
            echo(archiveURL)
        }
    }
}

def run(label, image, Closure main) {
    podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 60, containers: [
            containerTemplate(name: 'golang', image: image, alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
    ]) { node(label) { dir("tidb-tools") { main() } } }
}

catchError {
    run('build-go1130', 'hub.pingcap.net/jenkins/centos7_golang-1.13') { main() }
}
