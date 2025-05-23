@Library('tipipeline') _


# hello
final K8S_NAMESPACE = "jenkins-tispark"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tispark/latest/pod-pull_integration_test.yaml'
final PARALLEL_NUMBER = 18
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'java'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
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
            environment {
                PARALLEL_NUMBER = '18'
            }
            steps {
                dir(REFS.repo) {
                    sh label: 'split tests', script: '''
                        find core/src -name '*Suite*' | grep -v 'MultiColumnPKDataTypeSuite|PartitionTableSuite' > test
                        shuf test -o  test2
                        mv test2 test

                        # set when TEST_REGION_SIZE is not equal 'normal'
                        sed -i 's/# region-max-size = "2MB"/region-max-size = "2MB"/' config/tikv.toml
                        sed -i 's/# region-split-size = "1MB"/region-split-size = "1MB"/' config/tikv.toml"
                        cat config/tikv.toml

                        # set when TEST_MODE is not equal 'simple'
                        find core/src -name '*MultiColumnPKDataTypeSuite*' >> test

                        # set when TEST_TIFLASH is true
                        wget https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/ci/tispark/tidb_config-for-tiflash-test.properties

                        # set when TEST_SPARK_CATALOG is true
                        touch core/src/test/resources/tidb_config.properties
                        echo "spark.sql.catalog.tidb_catalog=org.apache.spark.sql.catalyst.catalog.TiCatalog" >> core/src/test/resources/tidb_config.properties

                        sed -i 's|core/src/test/scala/||g' test
                        sed -i 's|/|.|g' test
                        sed -i 's|.scala||g' test
                        split test -n r/$PARALLEL_NUMBER test_unit_ -a 2 --numeric-suffixes=1

                        wget -O core/src/test/resources/log4j.properties https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/ci/tispark/log4j-ci.properties
                        bash core/scripts/version.sh
                        bash core/scripts/fetch-test-data.sh
                        mv core/src/test core-test/src/
                        bash tikv-client/scripts/proto.sh
                    '''
                }
                dir(REFS.repo) {
                    script {
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tikv', REFS.base_ref, REFS.pulls[0].title, 'centos7/tikv-server.tar.gz', 'bin', trunkBranch="master", artifactVerify=true)
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'pd', REFS.base_ref, REFS.pulls[0].title, 'centos7/pd-server.tar.gz', 'bin', trunkBranch="master", artifactVerify=true)
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tiflash', REFS.base_ref, REFS.pulls[0].title, 'centos7/tiflash.tar.gz', 'bin', trunkBranch="master", artifactVerify=true)
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tidb', REFS.base_ref, REFS.pulls[0].title, 'centos7/tidb-server.tar.gz', 'bin', trunkBranch="master", artifactVerify=true)
                    }
                }

            }
        }
        stage('Tests') {

        }
    }
}
