def runUTFGo(args) {

    def ok = true

    def run = { suite ->
        stage("Run $suite") {
            try {
                build(job: 'utf-go-build', parameters: [
                    string(name: "SUITE", value: suite),
                    string(name: "TAG", value: "alpha1"),
                ])
            } catch (e) {
                println("Error: $e")
                ok = false
            }
        }
    }

    assert ok
}


def main(tag, branch, pr) {
    stage("Checkout-automated-tests") {
        container("python") { sh("chown -R 1000:1000 ./")}
        checkout(changelog: false, poll: false, scm: [
            $class           : "GitSCM",
            branches         : [[name: branch]],
            userRemoteConfigs: [[url: "https://github.com/PingCAP-QE/automated-tests.git",
                                 refspec: "+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*", credentialsId: "github-sre-bot"]],
            extensions       : [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
        ])
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
            withCredentials([string(credentialsId: "sre-bot-token", variable: 'GITHUB_TOKEN'), string(credentialsId: "cp-tcms-token", variable: 'TCMS_TOKEN'), string(credentialsId: "cp-jira-pwd", variable: 'JIRA_PASSWORD')]) {
                sh("""
                pip install ./framework
                apt-get update
                apt-get install -y python-dev libsasl2-dev gcc
                pip install -r requirements.txt
                git checkout origin/master
                python -m cases.cli case list --case-meta > test.log
                git checkout $branch
                git rebase origin/$branch
                bash /root/run.sh $pr
                """)
            }
        }
    }
}

def runUTFPy(args) {
    build(job: 'utf-py-build', parameters: [
        string(name: 'BRANCH', value: "pr/"+params.ghprbPullId),
    ])
    tag = "pr-"+params.ghprbPullId
    // try to create one_shot

    podTemplate(cloud: "kubernetes-ng", name: "utf-one-shot", namespace: "jenkins-qa", label: "utf-one-shot", instanceCap: 5, idleMinutes: 60, nodeSelector: "kubernetes.io/arch=amd64",
    containers: [
        containerTemplate(name: 'python', image: 'hub.pingcap.net/qa/utf-sync-version:latest', alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
    ]) { node("utf-one-shot") { dir("automated-tests") { main(tag, "pr/"+params.ghprbPullId, params.ghprbPullId) } } }
}

catchError {
    def args = params.EXTRA_ARGS
    args += " --annotation jenkins.trigger=$BUILD_URL"
    parallel(
        'Run UTF Go': { runUTFGo(args) },
        'Run UTF Py': { runUTFPy(args) },
    )
}
