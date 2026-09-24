final jobName = 'bloated'
pipelineJob("acme/widget/${jobName}") {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/acme/widget/latest/bloated/pipeline.groovy")
        }
    }
}
