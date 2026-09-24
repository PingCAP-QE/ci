final BRANCH_ALIAS = 'latest'
final GIT_FULL_REPO_NAME = "${REFS.org}/${REFS.repo}"
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/pod-refs.yaml"
pipeline { agent { kubernetes {} } }
