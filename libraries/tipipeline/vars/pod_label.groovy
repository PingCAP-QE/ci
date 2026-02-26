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

def buildCiAnnotations(def refs) {
    def prAuthor = null
    if (refs?.pulls && refs.pulls.size() > 0) {
        prAuthor = refs.pulls[0]?.author
    }
    def author = prAuthor ?: triggerUser()
    def rawAnnotations = [
        ci_pingcap_com_label_inject: 'true',
        ci_pingcap_com_job: env?.JOB_NAME,
        ci_pingcap_com_author: author,
        ci_pingcap_com_org: refs?.org,
        ci_pingcap_com_repo: refs?.repo,
        ci_pingcap_com_env: 'cicd',
    ]

    def annotations = [:]
    rawAnnotations.each { key, value ->
        def normalized = normalizeLabelValue(value)
        if (normalized) {
            annotations[key] = normalized
        }
    }
    return annotations
}

def triggerUser() {
    try {
        return getTriggerUserFromBuild(currentBuild?.rawBuild)
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

def normalizeLabelValue(def value) {
    if (value == null) {
        return null
    }
    def normalized = value.toString().toLowerCase()
    normalized = normalized.replaceAll('[^a-z0-9_-]', '_')
    if (normalized.length() > 63) {
        normalized = normalized.substring(0, 63)
    }
    normalized = normalized.replaceAll('^[-_]+|[-_]+$', '')
    return normalized ? normalized : null
}
