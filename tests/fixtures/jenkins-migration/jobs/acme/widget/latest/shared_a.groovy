final jobName = 'shared_a'
pipelineJob("acme/widget/${jobName}") {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/acme/widget/latest/shared/pipeline.groovy")
        }
    }
}
