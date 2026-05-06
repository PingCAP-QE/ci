/**
 * Unit tests for parseCIParamsFromPRTitle.
 *
 * Usage:
 *   groovy libraries/tipipeline/test/TestParseCIParams.groovy
 *
 * The function under test is a copy from libraries/tipipeline/vars/component.groovy.
 * It is a pure function with no Jenkins API dependencies, so it can be tested standalone.
 */

import static groovy.test.GroovyAssert.*

// ============================================================
// Function under test (copied from component.groovy)
// ============================================================
def parseCIParamsFromPRTitle(String prTitle) {
    def params = [:]
    def pipeIdx = prTitle.lastIndexOf('|')
    if (pipeIdx < 0) {
        return params
    }
    def afterPipe = prTitle.substring(pipeIdx + 1)
    if (afterPipe =~ /\(#\d+\)\s*$/) {
        return params
    }
    def paramReg = /\b([a-zA-Z][a-zA-Z0-9_-]*)\s*=\s*([^\s]+)/
    def matcher = (afterPipe =~ paramReg)
    matcher.each { match ->
        params[match[1]] = match[2]
    }
    return params
}

// ============================================================
// Test cases
// ============================================================
int passed = 0
int failed = 0

def assertEquals(Map expected, Map actual, String desc) {
    if (expected == actual) {
        passed++
        println "  ✅ PASS: ${desc}"
    } else {
        failed++
        println "  ❌ FAIL: ${desc}"
        println "      expected: ${expected}"
        println "      actual:   ${actual}"
    }
}

println "============================================"
println "Tests for parseCIParamsFromPRTitle"
println "============================================"

// --- Supported inputs ---

// Single param after pipe
assertEquals(
    [tidb: 'pr/123'],
    parseCIParamsFromPRTitle('feat: xxx | tidb=pr/123'),
    'single param: | tidb=pr/123'
)

// Multiple params after pipe
assertEquals(
    [tidb: 'pr/123', pd: '@v8.5.0'],
    parseCIParamsFromPRTitle('feat: xxx | tidb=pr/123 pd=@v8.5.0'),
    'multi params: | tidb=pr/123 pd=@v8.5.0'
)

// PR number before pipe (no space before |)
assertEquals(
    [tidb: 'pr/123'],
    parseCIParamsFromPRTitle('feat: xxx (#12467)| tidb=pr/123'),
    'PR# before pipe (no space): (#12467)| tidb=pr/123'
)

// PR number before pipe (with space before |)
assertEquals(
    [tidb: 'pr/123', pd: '@v8.5.0'],
    parseCIParamsFromPRTitle('feat: xxx (#12467) | tidb=pr/123 pd=@v8.5.0'),
    'PR# before pipe (with space): (#12467) | tidb=pr/123 pd=@v8.5.0'
)

// Multiple pipes — take the last segment
assertEquals(
    [tidb: 'pr/456', pd: '@v8.5.0'],
    parseCIParamsFromPRTitle('feat: xxx | tidb=pr/123 (#12456) | tidb=pr/456 pd=@v8.5.0'),
    'multi pipe: take last segment | tidb=pr/456 pd=@v8.5.0'
)

// Empty params (no pipe at all)
assertEquals(
    [:],
    parseCIParamsFromPRTitle('feat: xxx'),
    'no pipe: return empty'
)

// No params after pipe (cherry-pick suffix at end)
assertEquals(
    [:],
    parseCIParamsFromPRTitle('feat: xxx (#12467)'),
    'no pipe with PR# only: return empty'
)

// --- Rejected inputs (cherry-pick: (#NNNN) at end) ---

// Cherry-pick: PR number at end of pipe segment
assertEquals(
    [:],
    parseCIParamsFromPRTitle('feat: xxx | tidb=pr/123 pd=@v8.5.0 (#1235)'),
    'cherry-pick: | ... (#1235) → return empty'
)

// Cherry-pick: PR number at end with single param
assertEquals(
    [:],
    parseCIParamsFromPRTitle('feat: xxx | tidb=pr/123 (#12456)'),
    'cherry-pick: | tidb=pr/123 (#12456) → return empty'
)

// Cherry-pick in multi-pipe: first pipe has cherry-pick, last pipe has valid params
// Should match last pipe segment (which has no cherry-pick suffix)
assertEquals(
    [tidb: 'pr/456', pd: '@v8.5.0'],
    parseCIParamsFromPRTitle('feat: xxx | tidb=pr/123 (#12456) | tidb=pr/456 pd=@v8.5.0'),
    'cherry-pick first pipe, valid last pipe: take last segment'
)

// --- Edge cases ---

// Value with @ prefix
assertEquals(
    [pd: '@v8.5.0'],
    parseCIParamsFromPRTitle('feat: xxx | pd=@v8.5.0'),
    '@ prefix value: pd=@v8.5.0'
)

// Value with version-like tag
assertEquals(
    [tidb: 'v8.5.0'],
    parseCIParamsFromPRTitle('feat: xxx | tidb=v8.5.0'),
    'version tag: tidb=v8.5.0'
)

// Component name with hyphen
assertEquals(
    [:'my-component', value: 'test'],
    parseCIParamsFromPRTitle('feat: xxx | my-component=test'),
    'hyphen in component name: my-component=test'
)

// Whitespace around equals sign
assertEquals(
    [tidb: 'pr/123'],
    parseCIParamsFromPRTitle('feat: xxx | tidb = pr/123'),
    'spaces around =: tidb = pr/123'
)

// Nothing after pipe (whitespace only)
assertEquals(
    [:],
    parseCIParamsFromPRTitle('feat: xxx |  '),
    'empty after pipe: | (whitespace) → return empty'
)

// ============================================
println "============================================"
println "Results: ${passed} passed, ${failed} failed"
println "============================================"

if (failed > 0) {
    System.exit(1)
}
