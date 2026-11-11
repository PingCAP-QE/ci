import java.security.MessageDigest

// Cause that Jenkins attaches to a replayed build (workflow-cps). A replay reuses the
// original build's JOB_SPEC, so its cache key is identical and every stage that already
// succeeded would be skipped -- a replay that validates nothing. Recognising the cause
// keeps replays honest without changing cache keys, markers, or the marker layout.
@groovy.transform.Field
private final String REPLAY_CAUSE_CLASS = 'org.jenkinsci.plugins.workflow.cps.replay.ReplayCause'

// One bypass line per build is enough: matrix stages evaluate `when` once per axis.
@groovy.transform.Field
private boolean bypassReported = false

// Same for the degraded path: warn once per build, not once per stage check.
@groovy.transform.Field
private boolean causeWarningReported = false

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

    // Compress the key to a fixed length so it stays usable as a filename or a
    // JSON key. SHA-256, not MD5: the key decides whether a test stage is
    // skipped as "already passed", so a collision would silently drop coverage.
    // Changing the digest invalidates the markers written by earlier builds, so
    // every matrix stage runs once more and then caches normally.
    return MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes()).encodeHex().toString()
}

/**
 * Whether this build is a replay of an earlier build.
 *
 * The answer comes from the build causes, which are only readable through
 * `currentBuild`. When that is unavailable (older workflow-cps, restricted
 * sandbox, no pipeline context) the build is reported as "not replayed" so the
 * pipeline keeps working with its normal caching behaviour.
 */
def isReplayBuild() {
    try {
        def causes = currentBuild.getBuildCauses()
        return causes?.any { cause -> _isReplayCause(cause) } ?: false
    } catch (Exception e) {
        if (!causeWarningReported) {
            causeWarningReported = true
            _log("WARNING: matrixCache cannot read the build causes (${e}); " +
                "treating this build as not replayed")
        }
        return false
    }
}

/**
 * Determine whether the stage should be skipped (used in 'when' expressions).
 */
def shouldSkip(Map refs, String stageName, Map extraParams = [:]) {
    def bypassReason = _bypassReason()
    if (bypassReason != null) {
        _reportBypass(bypassReason)
        return false
    }

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

/**
 * Why the cache must be bypassed for this build, or null when it applies.
 */
private String _bypassReason() {
    if (isReplayBuild()) {
        return 'this is a replay build'
    }
    if (_isTrue(_paramValue('MATRIX_CACHE_BYPASS'))) {
        return 'MATRIX_CACHE_BYPASS=true'
    }
    return null
}

/**
 * Whether a build cause (as reported by `currentBuild.getBuildCauses()`, i.e. a
 * map with a `_class` field) is the replay cause.
 */
private boolean _isReplayCause(def cause) {
    if (!(cause instanceof Map)) {
        return false
    }
    def className = cause['_class']
    return className != null && className.toString().endsWith(REPLAY_CAUSE_CLASS)
}

/**
 * Read a pipeline parameter. Jobs triggered by Prow may declare no parameters at
 * all, in which case `params` simply yields null.
 */
private def _paramValue(String name) {
    try {
        return params?.get(name)
    } catch (Exception e) {
        return null
    }
}

private boolean _isTrue(def value) {
    return value != null && value.toString().trim().equalsIgnoreCase('true')
}

private void _reportBypass(String reason) {
    if (bypassReported) {
        return
    }
    bypassReported = true
    _log("matrix cache bypassed because ${reason}; every stage runs and no marker is read")
}

/**
 * Write to the build log. Falls back to stdout when the `echo` step is not bound,
 * so the helpers stay usable outside a pipeline run (e.g. the library test harness).
 */
private void _log(String message) {
    try {
        echo(message)
    } catch (MissingMethodException e) {
        println(message)
    }
}
