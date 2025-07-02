def fallback=false
pipeline{
    parameters{
        string(name: 'VERSION', defaultValue: '7.5.11', description: 'the grafana version')
        string(name: 'RELEASE_TAG', defaultValue: 'nightly', description: 'the tidb verison')
        string(name: 'RELEASE_BRANCH', defaultValue: 'master', description: 'target branch')
        string(name: 'TIDB_VERSION', defaultValue: '', description: 'tiup package verion')
        string(name: 'TIUP_MIRRORS', defaultValue: 'http://172.16.5.134:8987', description: 'tiup mirror')
    }
    agent {
        kubernetes {
            yaml '''
kind: Pod
spec:
  containers:
  - name: golang
    image: hub.pingcap.net/jenkins/centos7_golang-1.20
    args: ["sleep", "infinity"]
  - name: tiup
    image: hub.pingcap.net/jenkins/tiup
    args: ["sleep", "infinity"]
  nodeSelector:
    kubernetes.io/arch: amd64
'''
                    defaultContainer 'golang'
        }
}
    stages{
        stage("pull panel"){
            environment {
                GOPROXY = "http://goproxy.pingcap.net,https://proxy.golang.org,direct"
                TOKEN = credentials('sre-bot-token')
                TARGET = "${params.RELEASE_BRANCH}"
            }
            steps{
                checkout([$class: 'GitSCM',
                            branches: [[name: "${params.RELEASE_BRANCH}"]],
                            extensions: [[$class: 'LocalBranch']],
                            userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/monitoring.git']]]
                )
                script{
                    try{
                        sh "make output/dashboards"
                    }catch (Exception e) {
                        fallback = true
                        echo "fallback"
                        def  paramsBuild = [
                            string(name: "RELEASE_BRANCH", value: "${RELEASE_BRANCH}"),
                            string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
                            string(name: "ORIGIN_TAG", value: ""),
                            string(name: "TIDB_VERSION", value: "${TIDB_VERSION}"),
                            string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                            [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: true],
                            [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: true],
                            [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: true],
                            [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: true],
                        ]
                        echo "$paramsBuild"
                        build job: "grafana-tiup-mirror-update-test",
                                    wait: true,
                                    parameters: paramsBuild
                    }
                }
            }
        }
        stage("prepare tiup"){
            when {expression {!fallback}}
            environment { TIUPKEY_JSON = credentials('tiup-prod-key')}
            steps{
            container("tiup"){
                sh 'set +x;curl https://tiup-mirrors.pingcap.com/root.json -o /root/.tiup/bin/root.json; mkdir -p /root/.tiup/keys; cp $TIUPKEY_JSON /root/.tiup/keys/private.json'
            }
            }
        }
        stage("multi-arch"){
            when {expression {!fallback}}
            environment {
                TIUP_MIRRORS = "${params.TIUP_MIRRORS}"
            }
            matrix{
                axes {
                    axis {
                        name 'OS'
                        values 'linux', 'darwin'
                    }
                    axis {
                        name 'ARCH'
                        values 'amd64', 'arm64'
                    }
                }
                stages{
                    stage("tiup"){
                        options { retry(3) }
                        environment {
                            TARGET_OS="$OS"
                            TARGET_ARCH="$ARCH"
                        }
                        steps{
                            sh "make output/grafana-${OS}-${ARCH}.tar.gz"
                            container("tiup"){
                                sh """tiup mirror publish grafana ${params.TIDB_VERSION} output/grafana-${OS}-${ARCH}.tar.gz "bin/grafana-server" --arch $ARCH --os $OS --desc="Grafana is the open source analytics & monitoring solution for every database" """
                            }
                        }
                    }
                }
            }
        }
    }
}
