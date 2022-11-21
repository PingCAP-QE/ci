package latest

final K8S_CLUSTER = "kubernetes-ng"
final K8S_NAMESPACE = "jenkins-tidb-operator"
pipeline {
    agent none
    parameters {
        string(name: 'GitRef', defaultValue: 'master', description: 'branch or commit hash')
        string(name: 'ReleaseTag', defaultValue: 'test', description: 'empty means the same with GitRef')
    }
    stages {
        stage("BUILD") {
            environment { HUB = credentials('harbor-pingcap') }
            agent {
                kubernetes {
                    yamlFile 'build-tidb-dashboard-pod.yaml'
                    defaultContainer 'builder'
                    cloud K8S_CLUSTER
                    namespace K8S_NAMESPACE
                }
            }
            steps {
                checkout changelog: false, poll: false, scm: [
                        $class           : 'GitSCM',
                        branches         : [[name: "${params.GitRef}"]],
                        userRemoteConfigs: [[
                                                    refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pull/*',
                                                    url    : 'https://github.com/pingcap/tidb-dashboard.git',
                                            ]]
                ]
                sh 'printenv HUB_PSW | docker login -u $HUB_USR --password-stdin hub.pingcap.net'
                sh 'docker buildx create --name mybuilder --use || true'
                sh "docker buildx build . -f build-tidb-dashboard.Dockerfile -t hub.pingcap.net/rc/tidb-dashboard:${params.ReleaseTag} --push --platform=linux/arm64,linux/amd64 --build-arg BUILDKIT_INLINE_CACHE=1 --cache-from hub.pingcap.net/rc/tidb-dashboard"
                sh """docker run --platform=linux/amd64 --name=amd64 --ENTRYPOINT=/bin/cat  hub.pingcap.net/rc/tidb-dashboard:${params.ReleaseTag}
                          docker cp amd64:/tidb-dashboard bin/linux-amd64/tidb-dashboard
                          docker stop amd64
                          docker run --platform=linux/amd64 --name=arm64 --ENTRYPOINT=/bin/cat  hub.pingcap.net/rc/tidb-dashboard:${params.ReleaseTag}
                          docker cp arm64:/tidb-dashboard bin/linux-arm64/tidb-dashboard
                          docker stop arm64
                          docker container prune -f
                       """
                container("uploader") {
                    sh """tar -cvzf tidb-dashboard-linux-amd64.tar.gz -C bin/linux-amd64 tidb-dashboard
                              tar -cvzf tidb-dashboard-linux-arm64.tar.gz -C bin/linux-arm64 tidb-dashboard
                           """
                    sh "curl -F tmp/tidb-dashboard-linux-amd64.tar.gz=@tidb-dashboard-linux-amd64.tar.gz http://fileserver.pingcap.net/upload"
                }
            }

        }
    }
}
