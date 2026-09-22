// A job without a pod template: no POD_TEMPLATE_FILE constant is declared.
final GIT_FULL_REPO_NAME = 'acme/widget'
final BRANCH_ALIAS = 'latest'
pipeline { agent { kubernetes {} } }
