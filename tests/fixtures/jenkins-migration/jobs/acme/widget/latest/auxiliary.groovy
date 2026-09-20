final jobName = 'auxiliary'
pipelineJob("acme/widget/${jobName}") {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/acme/widget/latest/auxiliary/pipeline.groovy")
        }
    }
}
