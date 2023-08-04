// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_integration_br_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time:10, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir("third_party_download") {
                    retry(2) {
                        sh label: "download third_party", script: """
                            rm -rf third_bin/
                            rm -rf bin/
                            chmod +x ../tidb/br/tests/*.sh
                            ${WORKSPACE}/tidb/br/tests/download_integration_test_binaries.sh master
                            mkdir -p bin && mv third_bin/* bin/
                            ls -alh bin/
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                            ./bin/tiflash --version
                        """
                    }
                }
                dir('tidb') {
                    sh label: "check all tests added to group", script: """
                        #!/usr/bin/env bash
                        chmod +x br/tests/*.sh
                        ./br/tests/run_group.sh others
                    """
                    sh label: "prepare build", script: """
                        [ -f ./bin/tidb-server ] || make
                        [ -f ./bin/br.test ] || make build_for_br_integration_test
                        ls -alh ./bin
                        ./bin/tidb-server -V
                    """
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/br-lightning") { 
                        sh label: "prepare cache binary", script: """
                            cp -r ../third_party_download/bin/* ./bin/
                            ls -alh ./bin
                        """
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    name 'TEST_GROUP'
                    values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08', 'G09', 'G10', 'G11', 'G12', 'G13', 
                        'G14', 'G15', 'G16', 'G17'
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'golang'
                    }
                } 
                stages {
                    stage("Test") {
                        options { timeout(time: 45, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/br-lightning") { 
                                    sh label: "TEST_GROUP ${TEST_GROUP}", script: """
                                        #!/usr/bin/env bash
                                        chmod +x br/tests/*.sh
                                        ./br/tests/run_group.sh ${TEST_GROUP}
                                    """  
                                }
                            }
                            
                        }
                        post{
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/backup_restore_test
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/backup_restore_test/ -type f -name "*.log")    
                                    ls -alh  log-${TEST_GROUP}.tar.gz  
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", fingerprint: true 
                            }
                        }
                    }
                }
            }        
        }
    }
}
