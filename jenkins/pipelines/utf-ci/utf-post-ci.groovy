def GIT_REF_SPEC = "+refs/heads/master:refs/remotes/origin/master"

try {
    def label = 'utf-post-ci'
    podTemplate(cloud: "kubernetes-ng", name: label, namespace: "jenkins-qa", label: label, instanceCap: 3,
    idleMinutes: 1, nodeSelector: "kubernetes.io/arch=amd64",
    containers: [
        containerTemplate(name: 'python',
                          image: 'hub.pingcap.net/qa/utf-sync-version:latest',
                          alwaysPullImage: true,
                          ttyEnabled: true)
    ]) {
        build(job: 'utf-py-build', parameters: [
            string(name: 'BRANCH', value: "master"),
        ])
        node(label) {
            container('python') {
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
                                                refspec: GIT_REF_SPEC,
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

                stage("test") {
                    container("python") {
                        withCredentials([string(credentialsId: "cp-github-token", variable: 'GITHUB_TOKEN'), string(credentialsId: "cp-tcms-token", variable: 'TCMS_TOKEN'), string(credentialsId: "cp-jira-pwd", variable: 'JIRA_PASSWORD')]) {
                            sh("""
                            pip install ./framework
                            apt-get update
                            apt-get install -y python-dev libsasl2-dev gcc
                            pip install -r requirements.txt
                            bash /root/post_ci.sh
                            """)
                        }
                    }
                }
            }
        }
    }
    currentBuild.result = 'SUCCESS'
} finally {
    // Set commit status
    node(){
        try {
            step([
                $class: "GitHubCommitStatusSetter",
                reposSource: [$class: "ManuallyEnteredRepositorySource", url: "https://github.com/PingCAP-QE/automated-tests.git"],
                statusResultSource: [
                    $class: "ConditionalStatusResultSource",
                    results: [
                        [$class: 'BetterThanOrEqualBuildResult', result: 'SUCCESS', state: 'SUCCESS', message: "Jenkins job succeeded."],
                        [$class: 'BetterThanOrEqualBuildResult', result: 'FAILURE', state: 'FAILURE', message: "Jenkins job failed."],
                        [$class: "AnyBuildResult", state: 'ERROR', message: 'Jenkins job meets something wrong.']
                    ]
                ]
            ]);
        } catch (e) {
            echo "Failed to set commit status: $e"
        }
    }
}
