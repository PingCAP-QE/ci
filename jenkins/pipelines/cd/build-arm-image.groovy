def baseUrl = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-arm64/"
node("arm_image") {
   dir("go/src/github.com/pingcap/monitoring") {
       stage("prepare monitor") {
           deleteDir()
           checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: RELEASE_BRANCH]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/monitoring.git']]]
           sh """
              go build -o pull-monitoring  cmd/monitoring.go
              go build -o ./reload/build/linux/reload  ./reload/main.go
           """
           withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
               retry(3) {
                   sh """
                   ./pull-monitoring  --config=monitoring.yaml --tag=${RELEASE_TAG} --token=$TOKEN
                   ls monitor-snapshot/${RELEASE_TAG}/operator
                   """
               }
           }
       }
       stage("build monitor") {

           docker.withRegistry("", "dockerhub") {
               sh """
               export DOCKER_HOST=unix:///var/run/docker.sock
               cd monitor-snapshot/${RELEASE_TAG}/operator
               docker build  -t pingcap/tidb-monitor-initializer-arm64:${RELEASE_TAG} -f Dockerfile .
               docker push pingcap/tidb-monitor-initializer-arm64:${RELEASE_TAG}
               """
           }
       }
       stage("build reloader") {
           docker.withRegistry("", "dockerhub") {
               sh """
               export DOCKER_HOST=unix:///var/run/docker.sock
               cd reload
               wget ${baseUrl}tidb-monitor-reloader
               docker build  -t pingcap/tidb-monitor-reloader-arm64:v1.0.1 -f tidb-monitor-reloader .
               docker push pingcap/tidb-monitor-reloader-arm64:v1.0.1
               """
           }
       }
   }
}
