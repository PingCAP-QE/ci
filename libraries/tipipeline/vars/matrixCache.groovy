import groovy.json.JsonSlurper
import groovy.json.JsonOutput
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

    // 2. Automatically retrieve matrix axis variables.
    // Jenkins injects axis variables into env when running a matrix stage.
    // We filter out common axis variables based on naming conventions.
    def matrixContext = env.getEnvironment().findAll { k, v ->
        // Assume axis variables are uppercase or identifiable by a specific prefix.
        // It is recommended to pass them via function parameters or to exclude Jenkins default variables by convention.
        return k ==~ /[A-Z0-9_]+/ && !['WORKSPACE', 'HOME', 'PATH', 'BUILD_NUMBER', 'JOB_NAME'].contains(k)
    }.sort().collect { k, v -> "${k}=${v}" }.join(',')

    // 3. Combine extra parameters
    def extras = extraParams.sort().collect { k, v -> "${k}=${v}" }.join(',')

    // Assemble the raw string
    def rawKey = "${org}/${repo}/${baseSha}/${pullShas}/${stageName}/${matrixContext}/${extras}"

    // Use MD5 to compress the key length, preventing overly long filenames or JSON keys.
    return MessageDigest.getInstance("MD5").digest(rawKey.getBytes()).encodeHex().toString()
}

/**
 * Determine whether the stage should be skipped (used in 'when' expressions).
 */
def shouldSkip(Map refs, String stageName, Map extraParams = [:]) {
    def key = _generateContextKey(refs, stageName, extraParams)
    def cacheFile = new File("${env.JENKINS_HOME}/matrix-cache/${env.JOB_NAME.replaceAll('/', '_')}.json")

    if (!cacheFile.exists()) return false

    try {
        def json = new JsonSlurper().parse(cacheFile)
        return json[key] == "SUCCESS"
    } catch (e) {
        println "MatrixCache: failed to read cache: ${e.message}"
        return false
    }
}

/**
 * Mark the stage as done (used in 'post success' block).
 */
def markDone(Map refs, String stageName, Map extraParams = [:]) {
    def key = _generateContextKey(refs, stageName, extraParams)
    def cacheDir = new File("${env.JENKINS_HOME}/matrix-cache")
    if (!cacheDir.exists()) cacheDir.mkdirs()

    def cacheFile = new File(cacheDir, "${env.JOB_NAME.replaceAll('/', '_')}.json")
    def data = [:]

    // Use synchronized to ensure safe concurrent writes (matrix stages run in parallel).
    synchronized(this) {
        if (cacheFile.exists()) {
            data = new JsonSlurper().parse(cacheFile)
        }
        data[key] = "SUCCESS"
        cacheFile.text = JsonOutput.toJson(data)
    }
}
