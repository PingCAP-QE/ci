final GIT_FULL_REPO_NAME = 'acme/widget'
final BRANCH_ALIAS = 'latest'
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/pod-build.yaml"
pipeline { agent { kubernetes {} } }
