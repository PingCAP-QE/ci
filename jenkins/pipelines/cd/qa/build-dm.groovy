properties([
    parameters([
        string(name: 'FORK', defaultValue: 'pingcap', description: '', trim: true),
        string(name: 'BRANCH', defaultValue: 'master', description: '', trim: true),
        string(name: 'TIUP_MIRRORS', defaultValue: 'http://127.0.0.1:8989', description: '', trim: true),
        string(name: 'REFSPEC', defaultValue: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*', description: '', trim: true),
    ])
])

def main() {
    def archiveURL = ""
    def tag = params.BRANCH.replaceAll("/", "-")
    if (params.FORK != "pingcap") { tag = "${tag}-${params.FORK}" }
    stage("Checkout") {
        checkout(changelog: false, poll: false, scm: [
            $class           : "GitSCM",
            branches         : [[name: params.BRANCH]],
            userRemoteConfigs: [[url: "https://github.com/${params.FORK}/dm.git", refspec: params.REFSPEC]],
            extensions       : [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
        ])
    }
    stage("Build") {
        container("golang") {
            sh("make build")

            retry(3){
                sh("""
                cat > Dockerfile << __EOF__
FROM hub.pingcap.net/jenkins/centos:7

COPY bin/dmctl bin/dm-worker bin/dm-master bin/mydumper /bin/

ENTRYPOINT ["/bin/dmctl"]

# hub.pingcap.net/qa/dm
__EOF__
                curl -sL -o tici.sh http://fileserver.pingcap.net/download/pingcap/qa/scripts/tici.sh && chmod +x tici.sh
                curl -sL http://download.pingcap.org/tidb-enterprise-tools-latest-linux-amd64.tar.gz | tar -xz --strip-components 1
                """)
                archiveURL = sh(script: "./tici.sh publish pingcap/qa/archives dm ${tag} bin/ Dockerfile", returnStdout: true).trim()
            }
            echo(archiveURL)
        }
    }
    stage("Image") {
        build(job: "image-build", parameters: [
            string(name: "CONTEXT_URL", value: archiveURL),
            string(name: "DESTINATION", value: "hub.pingcap.net/qa/dm:${tag}"),
        ])
    }
}

def run(label, image, Closure main) {
    podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 60, containers: [
        containerTemplate(name: 'golang', image: image, alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
    ]) { node(label) { dir("dm") { main() } } }
}

catchError {
    run('build-go1164', 'hub.pingcap.net/pingcap/centos7_golang-1.16') { main() }
}
