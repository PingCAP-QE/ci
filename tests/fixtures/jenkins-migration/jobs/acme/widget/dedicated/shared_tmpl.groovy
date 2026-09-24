final fullRepo = 'acme/widget'
final jobName = 'shared_tmpl'
pipelineJob("${fullRepo}/dedicated/${jobName}") {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/${fullRepo}/latest/${jobName}/pipeline.groovy")
        }
    }
}
