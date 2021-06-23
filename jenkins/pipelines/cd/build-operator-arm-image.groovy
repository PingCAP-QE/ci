def baseUrl = "https://raw.githubusercontent.com/PingCAP-QE/ci/operator-arm64/jenkins/Dockerfile/release/"

node("arm_image") {
    stage("Prepare & build binary") {
        deleteDir()
        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: TIDB_OPERATOR_TAG]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb-operator.git']]]
        sh """
        export GOARCH=arm64
        make
        """
    }
    stage("Build Docker image") {
        operatorDockerfilePath = baseUrl + "tidb-operator-arm64"
        backupManagerDockerfilePath = baseUrl + "tidb-backup-manager-arm64"
        docker.withRegistry("", "dockerhub") {
            sh """
            cd images/tidb-operator && wget ${operatorDockerfilePath} -O Dockerfile
            docker build . -t pingcap/tidb-operator-arm64:${TIDB_OPERATOR_TAG}
            docker push pingcap/tidb-operator-arm64:${TIDB_OPERATOR_TAG}
            """

            sh """
            cd images/tidb-backup-manager && wget ${backupManagerDockerfilePath} -O Dockerfile
            docker build . -t pingcap/tidb-backup-manager-arm64:${TIDB_OPERATOR_TAG}
            docker push pingcap/tidb-backup-manager-arm64:${TIDB_OPERATOR_TAG}
            """
        }
    }
}