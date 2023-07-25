pipeline {
    agent none
    parameters {
        string defaultValue: 'latest', description: '', name: 'version', trim: true
    }
    stages {
        stage('update all image tag to latest') {
            matrix {
                axes {
                    axis {
                        name 'IMAGE'
                        values "pingcap/br" , "pingcap/dm" , "pingcap/dumpling" , "pingcap/ng-monitoring" , "pingcap/pd" , "pingcap/ticdc" , "pingcap/tidb" , "pingcap/tidb-binlog" , "pingcap/tidb-lightning" , "pingcap/tidb-monitor-initializer" , "pingcap/tiflash" , "pingcap/tikv" , "pingcap/br-enterprise" , "pingcap/dm-enterprise" , "pingcap/dumpling-enterprise" , "pingcap/ng-monitoring-enterprise" , "pingcap/pd-enterprise" , "pingcap/ticdc-enterprise" , "pingcap/tidb-enterprise" , "pingcap/tidb-binlog-enterprise" , "pingcap/tidb-lightning-enterprise" , "pingcap/tidb-monitor-initializer-enterprise" , "pingcap/tiflash-enterprise" , "pingcap/tikv-enterprise"
                    }
                }

                stages {
                    stage("update tag") {
                        steps {
                            echo "sync ${IMAGE}:${params.version} to ${IMAGE}:latest"
                            build job: 'jenkins-image-syncer',
                                    parameters: [
                                            string(name: 'SOURCE_IMAGE', value: "${IMAGE}:${params.version}"),
                                            string(name: 'TARGET_IMAGE', value: "${IMAGE}:latest")
                                    ]
                        }
                    }
                }
            }
        }
    }
}
