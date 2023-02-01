pipeline {
    agent none
    parameters {
        string defaultValue: 'latest', description: '', name: 'Version', trim: true
    }
    stages {
        stage('sync') {
            matrix {
                axes {
                    axis {
                        name 'prod'
                        values "br", "dm", "dumpling", "ng-monitoring", "pd", "ticdc", "tidb", "tidb-binlog", "tidb-lightning", "tidb-monitor-initializer", "tiflash", "tikv"
                    }
                }
                stages {
                    stage("sync") {
                        steps {
                            script{
                                def source = "hub.pingcap.net/qa/${prod}:${params.Version}-rocky-pre"
                                def target = "hub.pingcap.net/qa/${prod}:${params.Version}-pre"
                                echo "sync $source to $target"
                                build job: 'jenkins-image-syncer',
                                    parameters: [
                                            string(name: 'SOURCE_IMAGE', value: source),
                                            string(name: 'TARGET_IMAGE', value: target)
                                    ]
                                source = "hub.pingcap.net/qa/${prod}-enterprise:${params.Version}-rocky-pre"
                                target = "hub.pingcap.net/qa/${prod}-enterprise:${params.Version}-pre"
                                echo "sync $source to $target"
                                build job: 'jenkins-image-syncer',
                                    parameters: [
                                            string(name: 'SOURCE_IMAGE', value: source),
                                            string(name: 'TARGET_IMAGE', value: target)
                                    ]
                            }
                        }
                    }
                }
            }
        }
    }
}
