import java.security.MessageDigest

/**
 * Core logic: generate a unique key for the current build environment.
 */
def _generateContextKey(Map refs, String stageName, Map extraParams = [:]) {
    // 1. Extract key information from refs
    def org = refs.org ?: 'unknown'
    def repo = refs.repo ?: 'unknown'
    def baseSha = refs.base_sha ?: ''
    def pullShas = refs.pulls?.collect { it.sha }?.join('_') ?: 'no-pulls'

    // 2. Combine extra parameters.
    // Matrix axis/env values are NOT auto-included. Callers decide what to include
    // by passing explicit fields through extraParams.
    def extras = extraParams.sort().collect { k, v -> "${k}=${v}" }.join(',')

    // Assemble the raw string
    def rawKey = "${org}/${repo}/${baseSha}/${pullShas}/${stageName}/${extras}"

    // Use MD5 to compress the key length, preventing overly long filenames or JSON keys.
    return MessageDigest.getInstance("MD5").digest(rawKey.getBytes()).encodeHex().toString()
}

/**
 * Determine whether the stage should be skipped (used in 'when' expressions).
 */
def shouldSkip(Map refs, String stageName, Map extraParams = [:]) {
    def key = _generateContextKey(refs, stageName, extraParams)
    def cacheDir = new File("${env.JENKINS_HOME}/matrix-cache/${env.JOB_NAME.replaceAll('/', '_')}")
    def markerFile = new File(cacheDir, "${key}.success")
    return markerFile.exists()
}

/**
 * Mark the stage as done (used in 'post success' block).
 */
def markDone(Map refs, String stageName, Map extraParams = [:]) {
    def key = _generateContextKey(refs, stageName, extraParams)
    def cacheDir = new File("${env.JENKINS_HOME}/matrix-cache/${env.JOB_NAME.replaceAll('/', '_')}")
    if (!cacheDir.exists()) cacheDir.mkdirs()

    def markerFile = new File(cacheDir, "${key}.success")
    if (!markerFile.exists()) {
        markerFile.text = "SUCCESS"
    }
}
