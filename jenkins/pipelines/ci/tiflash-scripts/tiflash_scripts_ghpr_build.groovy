def checkoutTiFlash(commit, pullId) {
    checkout(changelog: false, poll: false, scm: [
            $class: "GitSCM",
            branches: [
                    [name: "${commit}"],
            ],
            userRemoteConfigs: [
                    [
                            url: "git@github.com:pingcap-inc/tiflash-scripts.git",
                            refspec: "+refs/pull/${pullId}/*:refs/remotes/origin/pr/${pullId}/*",
                            credentialsId: "github-sre-bot-ssh",
                    ]
            ],
            extensions: [
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
            ],
    ])
}

def fallback() {

    def label = "build-tiflash-fallback"

    catchError {

        podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 120, nodeSelector: "kubernetes.io/arch=amd64",containers: [
            containerTemplate(name: 'java', image: 'hub.pingcap.net/jenkins/centos7_golang-1.12_java', ttyEnabled: true, command: 'cat'),
        ]) {
            node(label) {
                stage("Checkout") {
                    dir("tiflash") {
                        checkoutTiFlash("${params.ghprbActualCommit}", "${params.ghprbPullId}")
                    }
                    dir("tispark") {
                        git url: "https://github.com/pingcap/tispark.git", branch: "tiflash-ci-test"
                        container("java") {
                            sh """
                            archive_url=${FILE_SERVER_URL}/download/builds/pingcap/tiflash/cache/tiflash-m2-cache_latest.tar.gz
                            if [ ! "\$(ls -A /maven/.m2/repository)" ]; then curl -sL \$archive_url | tar -zx -C /maven || true; fi
                            """
                            sh "mvn install -Dmaven.test.skip=true"
                        }
                    }
                }
                dir("tiflash") {
                    stage("Build") {
                        container("java") {
                            sh "cd computing/chspark && mvn install -DskipTests"
                        }
                    }
                    stage("Upload") {
                        echo "No need to upload currently."
                    }
                }
            }
        }

    }

    stage('Summary') {
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def msg = "Build Result: `${currentBuild.currentResult}`" + "\n" +
                "Elapsed Time: `${duration} mins`" + "\n" +
                "${env.RUN_DISPLAY_URL}"

        echo "${msg}"

    }

}

podTemplate(name: "toolkit-build-tiflash", label: "toolkit-build-tiflash", instanceCap: 3, idleMinutes: 30, nodeSelector: "kubernetes.io/arch=amd64",
    containers: [
    containerTemplate(name: 'toolkit', image: 'hub.pingcap.net/qa/ci-toolkit', ttyEnabled: true, command: 'cat'),
]) {

    node('toolkit-build-tiflash') {
        container('toolkit') {
            withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                if (sh(script: "inv resolve-dir --path .ci --repo pingcap-inc/tiflash-scripts --commit ${params.ghprbActualCommit} --auth sre-bot:${TOKEN} && test -f build.groovy", returnStatus: true) == 0) {
                    load 'build.groovy'
                } else {
                    fallback()
                }
            }
        }
    }

}
