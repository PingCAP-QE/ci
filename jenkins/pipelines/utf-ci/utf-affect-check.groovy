def main() {
    def tag = params.TAG
    if (tag == "") {
        tag = params.BRANCH.replaceAll("/", "-")
        if (params.FORK != "PingCAP-QE") { tag = "${params.FORK}__${tag}".toLowerCase() }
    }

    stage("Checkout-automated-tests") {
        checkout(
            changelog: false,
            poll: true,
            scm: [
                $class           : 'GitSCM',
                branches         : [[name: 'master']],
                extensions       : [[$class: 'PruneStaleBranch'],
                                    [$class: 'CleanBeforeCheckout'],
                                    [$class: 'UserIdentity', email: 'sre-bot@pingcap.com', name: 'sre-bot']],
                userRemoteConfigs: [[credentialsId: 'github-sre-bot',
                                    refspec: "+refs/heads/master:refs/remotes/origin/master",
                                    url: 'https://github.com/PingCAP-QE/automated-tests.git']],
            ]
        )
    }

    stage("Checkout-test-plan") {
        checkout(
            changelog: false,
            poll: true,
            scm: [
                $class           : 'GitSCM',
                branches         : [[name: 'main']],
                extensions       : [[$class: 'PruneStaleBranch'],
                                    [$class: 'CleanBeforeCheckout'],
                                    [$class: 'RelativeTargetDirectory', relativeTargetDir: '/home/jenkins/agent/workspace/test-plan/'],
                                    [$class: 'UserIdentity', email: 'sre-bot@pingcap.com', name: 'sre-bot']],
                userRemoteConfigs: [[credentialsId: 'github-sre-bot',
                                    refspec: "+refs/heads/main:refs/remotes/origin/main",
                                    url: 'https://github.com/PingCAP-QE/test-plan.git']],
            ]
        )
    }

    stage("Test") {
        container("python") {
            withCredentials([string(credentialsId: "cp-jira-pwd", variable: 'JIRA_PASSWORD'),string(credentialsId: "cp-github-token", variable: 'GITHUB_TOKEN'),]) {
                sh("""
                pip install ./framework
                apt-get update
                apt-get install -y python-dev libsasl2-dev gcc
                pip install -r requirements.txt
                python -m cases.cli case list --case-link > test.log
                python /root/sync-version/main.py check tibug --yaml-file /home/jenkins/agent/workspace/test-plan/compute/sqlfeature/utf-affectversion-auto.yaml --tibug-file /home/jenkins/agent/workspace/utf-affect-check/automated-tests/test.log
                python /root/sync-version/main.py check tibug --yaml-file /home/jenkins/agent/workspace/test-plan/compute/sqlfeature/utf-affectversion-auto1.yaml --tibug-file /home/jenkins/agent/workspace/utf-affect-check/automated-tests/test.log
                python /root/sync-version/main.py check yaml --dir /home/jenkins/agent/workspace/test-plan/compute/affected-versions --type version
                python /root/sync-version/main.py check yaml --dir /home/jenkins/agent/workspace/test-plan/compute/release --type branch
                python /root/sync-version/main.py check github --days 1
                python /root/sync-version/main.py check case
                python /root/sync-version/main.py report commits
                """)
            }
        }
    }
}

def run(label, image, Closure main) {
    podTemplate(cloud: "kubernetes-ng", name: label, namespace: "jenkins-qa", label: label, instanceCap: 5,
    idleMinutes: 0, nodeSelector: "kubernetes.io/arch=amd64",
    containers: [
        containerTemplate(name: 'python', image: image, alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
    ]) { node(label) { dir("automated-tests") { main() } } }
}

catchError {
    run('utf-jira-field', 'hub.pingcap.net/qa/utf-sync-version:latest') { main() }
}
