final fullRepo = 'acme/widget'
final jobName = 'shared_tmpl'
pipelineJob("${fullRepo}/${jobName}") {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/${fullRepo}/latest/${jobName}/pipeline.groovy")
        }
    }
}
