# Testing Jenkins Shared Library Code

This guide explains how to write and run unit tests for the Jenkins shared library code located in `libraries/tipipeline/`.

## Why Test Library Code?

The `libraries/tipipeline/vars/*.groovy` files contain utility functions used across many pipelines. Without tests:

- Regressions can silently break dozens of pipelines.
- Edge cases in branch name derivation, PR title parsing, etc. are easy to miss.
- Debugging failures in Jenkins replay is slow and painful.

Unit tests for **pure functions** (those with no Jenkins API dependency like `sh`, `checkout`, `echo`) can be run locally without a Jenkins server, giving rapid feedback.

## Prerequisites

- **Java**: OpenJDK 17+ (installed automatically by Homebrew)
- **Groovy**: 4.0+

Install on macOS:

```bash
brew install groovy
```

Verify:

```bash
groovy --version
# Groovy Version: 5.0.5 JVM: 25.0.2 Vendor: Homebrew OS: Mac OS X
```

## Test Directory Structure

Tests live under `libraries/tipipeline/test/`, mirroring the source layout:

```
libraries/tipipeline/
├── vars/              # Shared library functions (source under test)
│   ├── component.groovy
│   ├── cdc.groovy
│   ├── prow.groovy
│   └── ...
├── src/               # Optional: helper classes
└── test/              # Unit tests (you are here)
    ├── TestComponent.groovy
    └── ...            # More test files as needed
```

## Writing Tests

### Framework

We use **JUnit 4** with Groovy. JUnit 4 is bundled with the Groovy distribution, so no extra dependencies are needed.

Test classes use `@RunWith(Enclosed.class)` to group related test suites:

```groovy
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.experimental.runners.Enclosed
import static org.junit.Assert.*

@RunWith(Enclosed.class)
class TestSomeFunction {

    static class BehaviorGroupA {
        // tests for one aspect
    }

    static class BehaviorGroupB {
        // tests for another aspect
    }
}
```

### Loading the Function Under Test

Functions are loaded from the source file at runtime using `GroovyShell`. This avoids duplicating code:

```groovy
static class ParseCIParams {

    private def parseCIParams

    @Before
    void setUp() {
        def script = new GroovyShell().parse(
            new File("libraries/tipipeline/vars/component.groovy"))
        parseCIParams = { String title ->
            script.invokeMethod('parseCIParamsFromPRTitle', [title])
        }
    }

    // ... tests
}
```

**Why this works**: `GroovyShell.parse()` only compiles the code — it does not execute it. Functions that reference Jenkins APIs (like `sh`, `checkout`) are compiled but never called during the test, so they cause no errors. Only the function under test (a pure function) is invoked.

### Table-Driven Tests

We use **table-driven tests** to reduce boilerplate. Each `@Test` method defines a table of cases and iterates over them:

```groovy
@Test
void shouldExtractParamsFromTitle() {
    def cases = [
        [title: 'feat: support fast read | tidb=pr/123',
         expected: [tidb: 'pr/123']],
        [title: 'feat: support fast read | tidb=pr/123 pd=@v8.5.0',
         expected: [tidb: 'pr/123', pd: '@v8.5.0']],
        // ... more cases
    ]

    cases.each { c ->
        def result = parseCIParams(c.title)
        assertEquals("case: ${c.title}", c.expected, result)
    }
}
```

**Benefits**:
- Adding a new test case is a one-line addition to the table.
- The assertion message includes the case description, making failures easy to diagnose.
- No need for repetitive `@Test` methods.

### Test Method Naming

Name test methods in `should` style to describe the expected behavior:

```groovy
@Test
void shouldExtractSingleParamAfterPipe() { ... }

@Test
void shouldIgnoreParamOnCherryPickTitle() { ... }

@Test
void shouldDeriveBranchFromTarget() { ... }
```

### Assertion Messages

Always include a descriptive message as the first argument to `assertEquals`:

```groovy
assertEquals("${component} on ${target}: ${title}", expected, result)
```

This makes test failures instantly readable.

## Running Tests

Run a single test file with the `groovy` command:

```bash
groovy libraries/tipipeline/test/TestComponent.groovy
```

Groovy automatically detects JUnit 4 annotations and runs the tests. Example output:

```
computeBranchFromPR component: tidb, prTargetBranch: master, prTitle: feat: support fast read | tidb=pr/123, trunkBranch: master
JUnit 4 Runner, Tests: 6, Failures: 0, Time: 925
```

Exit code is `0` on success and `1` on failure, so it integrates with CI scripts:

```bash
groovy libraries/tipipeline/test/TestComponent.groovy || {
    echo "Tests failed!"
    exit 1
}
```

## Best Practices

### DO

- **Test pure functions only**. Functions that call `sh`, `checkout`, `withCredentials`, etc. cannot run outside Jenkins. Keep these dependencies minimal and extract pure logic into testable helper functions.
- **Use table-driven style**. It minimizes boilerplate and makes adding new cases trivial.
- **Cover edge cases**. Empty input, special characters, boundary values, cherry-pick patterns, etc.
- **Name tests as specifications**. `shouldReturnEmptyWhenNoPipe()` is better than `testPipe()`, and much better than `test1()`.
- **Include assertion messages**. They save debugging time when a case fails.
- **Keep tests fast**. Each test file should complete in under 5 seconds. If a test is slow, it won't be run frequently.

### DON'T

- **Don't test Jenkins internals**. Focus on your logic, not on whether `sh` works.
- **Don't duplicate the function under test**. Always load it from the source file with `GroovyShell`.
- **Don't write one method per case**. Use tables instead.
- **Don't skip error messages in assertions**. A bare `assertEquals(expected, actual)` gives no context on failure.

## Example: Real Test File

See `libraries/tipipeline/test/TestComponent.groovy` for a complete example testing:

- `parseCIParamsFromPRTitle` — extracting CI parameters from PR titles
- `computeBranchFromPR` — deriving component branches from target branches and PR titles

Both functions are pure (no Jenkins API calls) and the tests cover 27+ cases across 6 `@Test` methods using table-driven style.

## Adding a New Test File

1. Create `libraries/tipipeline/test/Test<YourFunction>.groovy`
2. Import JUnit 4 classes and `@RunWith(Enclosed.class)` for nested suites
3. Load the source function via `GroovyShell` in `@Before`
4. Write table-driven `@Test` methods
5. Run with `groovy libraries/tipipeline/test/Test<YourFunction>.groovy`
