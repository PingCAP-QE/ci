final jobName = 'aux'
pipelineJob("acme/widget/${jobName}") {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/acme/widget/latest/aux/pipeline.groovy")
        }
    }
}
