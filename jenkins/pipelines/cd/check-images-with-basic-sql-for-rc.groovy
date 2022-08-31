pipeline {
    parameters {
        string defaultValue: 'master', description: 'example v5.2.4', name: 'version', trim: true
    }
    agent {
        kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: tcctl
      image: hub.pingcap.net/qa/tcctl
      command:
        - sleep
      args:
        - infinity
'''
            defaultContainer 'tcctl'
        }
    }
    options { skipDefaultCheckout() }
    environment {
        TOKEN = credentials('tcms-token')
    }
    stages {
        stage('Main') {
            steps {
                script {
                    sh "tcctl svc run tidb-basic-sql-check -a tidbVersion=${params.version},tikvVersion=${params.version},pdVersion=${params.version},tiflashVersion=${params.version} -o -"
                }
            }
        }
    }
}
