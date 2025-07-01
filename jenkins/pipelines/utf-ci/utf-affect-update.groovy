def main() {
    def tag = params.TAG
    if (tag == "") {
        tag = params.BRANCH.replaceAll("/", "-")
        if (params.FORK != "PingCAP-QE") { tag = "${params.FORK}__${tag}".toLowerCase() }
    }

    stage("Checkout") {
        container("python") { sh("chown -R 1000:1000 ./")}
        checkout(changelog: false, poll: false, scm: [
            $class           : "GitSCM",
            branches         : [[name: params.BRANCH]],
            userRemoteConfigs: [[url: "https://github.com/${params.FORK}/test-plan.git",
                                 refspec: params.REFSPEC, credentialsId: "github-sre-bot"]],
            extensions       : [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
        ])
    }

    stage("Test") {
        container("python") {
            withCredentials([string(credentialsId: "cp-github-token", variable: 'GITHUB_TOKEN'), string(credentialsId: "cp-tcms-token", variable: 'TCMS_TOKEN'), string(credentialsId: "cp-jira-pwd", variable: 'JIRA_PASSWORD')]) {
                sh("""
                bash /root/affect_update.sh
                """)
            }
        }
    }
}

def run(label, image, Closure main) {
    podTemplate(cloud: "kubernetes-ng", name: label, namespace: "jenkins-qa", label: label, instanceCap: 5,
    idleMinutes: 60, nodeSelector: "kubernetes.io/arch=amd64",
    containers: [
        containerTemplate(name: 'python', image: image, alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
    ]) { node(label) { dir("test-plan") { main() } } }
}

catchError {
    run('utf-affect-update', 'hub.pingcap.net/qa/utf-sync-version:latest') { main() }
}
