final folder = 'acme/widget/latest'
final jobName = 'refs'
pipelineJob("${folder}/${jobName}") {
    definition {
        cpsScm {
            lightweight(true)
            scriptPath("pipelines/${folder}/${jobName}.groovy")
        }
    }
}
