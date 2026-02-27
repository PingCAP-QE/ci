def withCiLabels(String podTemplateFile, def refs) {
    def podYaml = null
    try {
        podYaml = readTrusted(podTemplateFile)
    } catch (Exception e) {
        log.warning("pod_label: failed to read pod template ${podTemplateFile}: ${e.message}")
        return ''
    }
    if (podYaml == null || !podYaml.toString().trim()) {
        log.warning("pod_label: empty pod template ${podTemplateFile}, skip label injection")
        return podYaml ?: ''
    }

    def podSpec = null
    try {
        podSpec = readYaml(text: podYaml)
    } catch (Exception e) {
        log.warning("pod_label: invalid pod yaml in ${podTemplateFile}: ${e.message}, skip label injection")
        return podYaml
    }
    if (!(podSpec instanceof Map)) {
        log.warning("pod_label: pod yaml in ${podTemplateFile} is not a map, skip label injection")
        return podYaml
    }

    def annotations = buildCiAnnotations(refs)
    if (annotations.isEmpty()) {
        return podYaml.toString().trim()
    }

    def metadata = (podSpec.metadata instanceof Map) ? podSpec.metadata : [:]
    def existingAnnotations = (metadata.annotations instanceof Map) ? metadata.annotations : [:]
    metadata.annotations = existingAnnotations + annotations
    podSpec.metadata = metadata

    return writeYaml(returnText: true, data: podSpec).trim()
}

// For declarative pipelines: provide a YAML snippet with CI annotations.
def buildCiAnnotationsYaml(def refs) {
    def annotations = buildCiAnnotations(refs)
    if (annotations.isEmpty()) {
        return ''
    }
    def lines = [
        'apiVersion: v1',
        'kind: Pod',
        'metadata:',
        '  annotations:',
    ]
    annotations.each { key, value ->
        lines << "    ${key}: ${escapeYamlValue(value.toString())}"
    }
    return lines.join('\n')
}

def escapeYamlValue(String value) {
    def escaped = value.replace('\\', '\\\\').replace('"', '\\"')
    return "\"${escaped}\""
}

// For declarative pipelines: build CI annotations map.
def buildCiAnnotations(def refs) {
    def rawAnnotations = [
        ci_job: env?.JOB_NAME,
        ci_refs: refs ? groovy.json.JsonOutput.toJson(refs) : null,
        ci_trigger_user: triggerUser(),
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
