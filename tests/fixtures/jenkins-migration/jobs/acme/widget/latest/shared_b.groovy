final jobName = 'shared_b'
pipelineJob("acme/widget/${jobName}") {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/acme/widget/latest/shared/pipeline.groovy")
        }
    }
}
