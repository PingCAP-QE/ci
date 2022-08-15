components = ['tidb', 'tikv', 'pd', 'tiflash']

def componentsVersions(String hotfixVersion, String component){
    String baseVersion = hotfixVersion.split("-")[0]
    def componentsVersions = [:]
    for (comp in components) {
        componentsVersions[comp] = baseVersion
    }
    componentsVersions[component] = hotfixVersion
    return componentsVersions
}

pipeline {
    parameters {
        string defaultValue: 'master', description: 'example v5.2.4-20220804', name: 'hotfixVersion', trim: true
        choice choices: components, description: '', name: 'component'
    }
    agent {
        kubernetes {
            yamlFile 'tcctl-pod.yaml'
        }
    }
    environment {
        token     = credentials('tcms-token')
    }
    stages {
        stage('Main') {
            steps {
                script{
                    def versions = componentsVersions(params.hotfixVersion, params.component)
                    sh "tcctl svc run tidb-basic-sql-check -a tidbVersion=${versions.tidb},tikvVersion=${versions.tikv},pdVersion=${versions.pd},tiflashVersion=${versions.tiflash} -o -"
                }
            }
        }
    }
}
