final POD_TEMPLATE_FILE_BUILD = "pipelines/acme/widget/latest/bloated/pod-bloated-build.yaml"
final POD_TEMPLATE_FILE_TEST = "pipelines/acme/widget/latest/bloated/pod-bloated-test.yaml"
pipeline { agent { kubernetes {} } }
