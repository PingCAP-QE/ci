final jobName = 'single'
pipelineJob("acme/widget/${jobName}") {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/acme/widget/latest/single/pipeline.groovy")
        }
    }
}
