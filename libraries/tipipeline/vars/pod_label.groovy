def withCiLabels(String podTemplateFile, def refs) {
    def podYaml = readFile(podTemplateFile)
    try {
        def podSpec = readYaml(text: podYaml)
        if (!(podSpec instanceof Map)) {
            log.warning("pod_label: invalid pod yaml in ${podTemplateFile}, skip label injection")
            return podYaml
        }

        def labels = buildCiLabels(refs)
        if (labels.isEmpty()) {
            return podYaml
        }

        def metadata = (podSpec.metadata instanceof Map) ? podSpec.metadata : [:]
        def existingLabels = (metadata.labels instanceof Map) ? metadata.labels : [:]
        metadata.labels = existingLabels + labels
        podSpec.metadata = metadata

        return writeYaml(returnText: true, data: podSpec).trim()
    } catch (Exception e) {
        log.warning("pod_label: failed to inject labels into ${podTemplateFile}: ${e.message}")
        return podYaml
    }
}

def buildCiLabels(def refs) {
    def prAuthor = null
    if (refs?.pulls && refs.pulls.size() > 0) {
        prAuthor = refs.pulls[0]?.author
    }
    def owner = prAuthor ?: triggerUser()
    def rawLabels = [
        owner: owner,
        org: refs?.org,
        repo: refs?.repo,
        env: 'cicd',
    ]

    def labels = [:]
    rawLabels.each { key, value ->
        def normalized = normalizeLabelValue(value)
        if (normalized) {
            labels[key] = normalized
        }
    }
    return labels
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
