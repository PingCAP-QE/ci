final GIT_FULL_REPO_NAME = 'acme/widget'
final BRANCH_ALIAS = 'latest'
final POD_TEMPLATE_FILE_BUILD = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod-build.yaml"
final POD_TEMPLATE_FILE_TEST = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod-test.yaml"
pipeline { agent { kubernetes {} } }
