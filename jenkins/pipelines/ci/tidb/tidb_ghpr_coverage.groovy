

final GIT_FULL_REPO_NAME="pingcap/tidb"

final podYaml = '''
apiVersion: v1
kind: Pod
spec:
  securityContext:
    fsGroup: 1000
  containers:
    - name: golang
      image: "hub.pingcap.net/jenkins/centos7_golang-1.19:latest"
      tty: true
      resources:
        requests:
          memory: 12Gi
          cpu: "4"
        limits:
          memory: 16Gi
          cpu: "6"
    - name: net-tool
      image: wbitt/network-multitool
      tty: true
      resources:
        limits:
          memory: 128Mi
          cpu: 100m
    - name: report
      image: hub.pingcap.net/jenkins/python3-requests:latest
      tty: true
      resources:
        limits:
          memory: 256Mi
          cpu: 100m
'''

pipeline {
    agent {
        kubernetes {
            yaml podYaml
            defaultContainer 'golang'
        }
    }
    triggers {
        cron('@daily')
    }
    parameters {
        string(name: 'GIT_BRANCH', defaultValue: 'master', description: '')
        string(name: 'GIT_COMMIT', defaultValue: 'master', description: '')
    }

    stages {
        stage("Checkout") {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    retry(2) {
                        sh """
                        wget -O tidb.tar.gz  ${FILE_SERVER_URL}/download/cicd/daily-cache-code/tidb.tar.gz -q --show-progress
                        tar -xzf tidb.tar.gz -C ./ --strip-components=1  && rm -rf tidb.tar.gz
                        """
                        checkout(
                            changelog: false,
                            poll: false,
                            scm: [
                                $class: 'GitSCM', branches: [[name: GIT_BRANCH ]],
                                doGenerateSubmoduleConfigurations: false,
                                extensions: [
                                    [$class: 'PruneStaleBranch'],
                                    [$class: 'CleanBeforeCheckout'],
                                    [$class: 'CloneOption', timeout: 15],
                                ],
                                submoduleCfg: [],
                                userRemoteConfigs: [[
                                    refspec: "+refs/heads/*:refs/remotes/origin/*",
                                    url: "https://github.com/${GIT_FULL_REPO_NAME}.git",
                                ]],
                            ]
                        )
                    }  
                }
            }
        }

        stage("Test") {
            options { timeout(time: 30, unit: "MINUTES") }
            steps {
                dir("tidb") {
                    sh label: "ut", script: """
                    git status && git rev-parse HEAD
                    ./build/jenkins_unit_test.sh
                    """
                }
            }
            post {
                success {
                    dir("tidb") {
                        sh label: "upload coverage to codecov", script: """
                        mv coverage.dat test_coverage/coverage.dat
                        wget -q -O codecov ${FILE_SERVER_URL}/download/cicd/tools/codecov-v0.3.2
                        chmod +x codecov
                        ./codecov --dir test_coverage/ --token ${TIDB_CODECOV_TOKEN}
                        """
                    }
                }
            }
        }
    }
}