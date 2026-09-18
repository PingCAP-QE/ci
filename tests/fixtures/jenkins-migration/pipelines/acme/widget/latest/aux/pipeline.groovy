final SELF_DIR = "pipelines/acme/widget/latest/aux"
final POD_INTEGRATIONTEST_TEMPLATE_FILE = "${SELF_DIR}/pod.yaml"
final SHARED_HELPER = "pipelines/acme/widget/latest/common/helper.sh"
pipeline {
    agent { kubernetes {} }
    stages {
        stage('Run') {
            steps {
                sh "${WORKSPACE}/${SELF_DIR}/run.sh"
                sh "${WORKSPACE}/${SHARED_HELPER}"
            }
        }
    }
}
