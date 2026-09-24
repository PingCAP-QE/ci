final jobName = 'multi'
pipelineJob("acme/widget/${jobName}") {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/acme/widget/latest/multi/pipeline.groovy")
        }
    }
}
