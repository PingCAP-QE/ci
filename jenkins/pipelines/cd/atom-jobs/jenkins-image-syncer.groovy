final dockerSyncYaml = '''
spec:
  containers:
  - name: regctl
    image: hub.pingcap.net/jenkins/regctl
    args: ["sleep", "infinity"]
'''
pipeline{
    parameters {
        string(name: 'SOURCE_IMAGE', description: 'source image name, habor')
        string(name: 'TARGET_IMAGE', description: 'target image name, gcr or dockerhub')
    }
    agent {
        kubernetes {
            yaml dockerSyncYaml
            defaultContainer 'regctl'
        }
    }
    options {
      skipDefaultCheckout true
      retry(3)
    }
    stages{
      stage("login gcr") {
        environment { HUB = credentials('gcr-registry-key') }
        when { expression { params.TARGET_IMAGE.startsWith('gcr.io/') } }
        steps {
            sh 'set +x; regctl registry login gcr.io -u _json_key -p "$(cat $(printenv HUB))"'
        }
      }
      stage("login harbor") {
        environment { HUB = credentials('harbor-pingcap') }
        when { expression { params.TARGET_IMAGE.startsWith('hub.pingcap.net/') } }
        steps {
            sh 'set +x; regctl registry login hub.pingcap.net -u $HUB_USR -p $(printenv HUB_PSW)'
        }
      }
      stage("login dockerhub") {
        when { expression { params.TARGET_IMAGE.startsWith('pingcap/') || params.TARGET_IMAGE.startsWith('docker.io/') } }
        environment { HUB = credentials('dockerhub-pingcap') }
        steps {
            sh 'set +x; regctl registry login docker.io -u $HUB_USR -p $(printenv HUB_PSW)'
        }
      }
      stage("sync"){
        steps {
          sh "regctl image copy ${SOURCE_IMAGE}  ${TARGET_IMAGE}"
      }
    }
  }
}
