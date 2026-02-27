def withCiLabels(String podTemplateFile, def refs) {
    def podYaml = readFile(podTemplateFile)
    try {
        def podSpec = readYaml(text: podYaml)
        if (!(podSpec instanceof Map)) {
            log.warning("pod_label: invalid pod yaml in ${podTemplateFile}, skip label injection")
            return podYaml
        }

        def annotations = buildCiAnnotations(refs)
        if (annotations.isEmpty()) {
            return podYaml
        }

        def metadata = (podSpec.metadata instanceof Map) ? podSpec.metadata : [:]
        def existingAnnotations = (metadata.annotations instanceof Map) ? metadata.annotations : [:]
        metadata.annotations = existingAnnotations + annotations
        podSpec.metadata = metadata

        return writeYaml(returnText: true, data: podSpec).trim()
    } catch (Exception e) {
        log.warning("pod_label: failed to inject annotations into ${podTemplateFile}: ${e.message}")
        return podYaml
    }
}

// For declarative pipelines: merge CI annotations into a pod YAML template string.
def buildCiAnnotations(def refs) {
    def rawAnnotations = [
        ci_pingcap_com_job: env?.JOB_NAME,
        ci_pingcap_com_refs: refs ? groovy.json.JsonOutput.toJson(refs) : null,
        ci_pingcap_com_trigger_user: triggerUser(),
    ]

    def annotations = [:]
    rawAnnotations.each { key, value ->
        if (value != null && value.toString().trim()) {
            annotations[key] = value.toString()
        }
    }
    return annotations
}

// For scripted pipelines: convert CI annotations to podTemplate annotations list.
def buildCiAnnotationsList(def refs) {
    def annotations = buildCiAnnotations(refs)
    return annotations.collect { key, value ->
        [key: key, value: value]
    }
}

def triggerUser() {
    try {
        def user = getTriggerUserFromBuild(currentBuild?.rawBuild)
        if (!user) {
            user = env?.BUILD_USER_ID ?: env?.BUILD_USER
        }
        return user
    } catch (Exception e) {
        log.warning("pod_label: failed to determine trigger user: ${e.message}")
        return null
    }
}

@NonCPS
def getTriggerUserFromBuild(def rawBuild) {
    if (!rawBuild) {
        return null
    }
    def cause = rawBuild.getCause(hudson.model.Cause.UserIdCause)
    return cause?.userId ?: cause?.userName
}
