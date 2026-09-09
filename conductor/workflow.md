# Project Workflow

## Guiding Principles

1. **The Plan is the Source of Truth:** All work must be tracked in `plan.md`
2. **The Tech Stack is Deliberate:** Changes to the tech stack must be documented in `tech-stack.md` *before* implementation
3. **Test-Driven Development:** Write unit tests before implementing functionality
4. **High Code Coverage:** Aim for >80% code coverage for all modules
5. **Reliability & Clarity First:** Every decision should keep product CI reliable and keep job/pipeline configs clear and reviewable for the CI owners and product developers who consume them
6. **Non-Interactive & CI-Aware:** Prefer non-interactive commands. Use `CI=true` for watch-mode tools (tests, linters) to ensure single execution.

## Task Workflow

All tasks follow a strict lifecycle:

**Workflow Preferences (configured at setup):**
- Test code coverage target: >80%
- Commit frequency: **per phase** (code is committed once at the end of each phase, not after every task)
- Task/phase summaries: recorded as **Git notes** attached to the phase checkpoint commit

**Where unit tests apply:** TDD and coverage targets apply to *code modules* (Go tools under `tools/`, Deno/TypeScript scripts, Groovy shared-library functions). Config-only changes (`.yaml` Prow/Tekton, Groovy Job DSL / pipelines) have no unit tests; they are verified with the repo's validation scripts and by triggering/replaying the affected job in staging.

### Standard Task Workflow

1. **Select Task:** Choose the next available task from `plan.md` in sequential order

2. **Mark In Progress:** Before beginning work, edit `plan.md` and change the task from `[ ]` to `[~]`

3. **Write Failing Tests (Red Phase):**
   - Create a new test file for the feature or bug fix.
   - Write one or more unit tests that clearly define the expected behavior and acceptance criteria for the task.
   - **CRITICAL:** Run the tests and confirm that they fail as expected. This is the "Red" phase of TDD. Do not proceed until you have failing tests.

4. **Implement to Pass Tests (Green Phase):**
   - Write the minimum amount of application code necessary to make the failing tests pass.
   - Run the test suite again and confirm that all tests now pass. This is the "Green" phase.

5. **Refactor (Optional but Recommended):**
   - With the safety of passing tests, refactor the implementation code and the test code to improve clarity, remove duplication, and enhance performance without changing the external behavior.
   - Rerun tests to ensure they still pass after refactoring.

6. **Verify Coverage:** Run coverage reports for changed *code modules* only. Examples:
   ```bash
   cd tools/<tool> && go test ./... -cover                 # Go tools
   deno test                                               # Deno tools (non-watch; deno.json "test" task uses --watch)
   groovy libraries/tipipeline/tests/TestComponent.groovy  # shared library functions
   ```
   Target: >80% coverage for new code in code modules. Config-only changes skip this step.

7. **Document Deviations:** If implementation differs from tech stack:
   - **STOP** implementation
   - Update `tech-stack.md` with new design
   - Add dated note explaining the change
   - Resume implementation

8. **Record Completion in Plan (No Per-Task Commit):**
   - Read `plan.md`, find the line for the completed task, and update its status from `[~]` to `[x]`.
   - Commit frequency is **per phase**: do **not** create an individual commit for this task yet. Leave the code changes and the `plan.md` update in the working tree until the phase concludes.
   - Write the updated content back to `plan.md`.

9. **Prepare Task Summary (Git Notes):**
   - Draft a concise summary for the completed task: the task name, a summary of changes, a list of all created/modified files, and the core "why" for the change.
   - The summary is attached as a **Git note** to the phase checkpoint commit created by the "Phase Completion Verification and Checkpointing Protocol" below.

10. **Commit at Phase Completion:**
    - If the completed task concludes a phase in `plan.md`, do **not** commit yet. Instead, execute the "Phase Completion Verification and Checkpointing Protocol" below, which performs the single phase commit, attaches the phase summary Git note(s), and records the phase checkpoint SHA in `plan.md`.

### Phase Completion Verification and Checkpointing Protocol

**Trigger:** This protocol is executed immediately after a task is completed that also concludes a phase in `plan.md`.

1.  **Announce Protocol Start:** Inform the user that the phase is complete and the verification and checkpointing protocol has begun.

2.  **Ensure Test Coverage for Phase Changes:**
    -   **Step 2.1: Determine Phase Scope:** To identify the files changed in this phase, you must first find the starting point. Read `plan.md` to find the Git commit SHA of the *previous* phase's checkpoint. If no previous checkpoint exists, the scope is all changes since the first commit.
    -   **Step 2.2: List Changed Files:** Execute `git diff --name-only <previous_checkpoint_sha> HEAD` to get a precise list of all files modified during this phase.
    -   **Step 2.3: Verify and Create Tests:** For each file in the list:
        -   **CRITICAL:** First, check its extension. Exclude non-code files (e.g., `.json`, `.md`, `.yaml`).
        -   For each remaining code file, verify a corresponding test file exists.
        -   If a test file is missing, you **must** create one. Before writing the test, **first, analyze other test files in the repository to determine the correct naming convention and testing style.** The new tests **must** validate the functionality described in this phase's tasks (`plan.md`).

3.  **Execute Automated Tests with Proactive Debugging:**
    -   Before execution, you **must** announce the exact shell command you will use to run the tests.
    -   **Example Announcement:** "I will now run the automated test suite to verify the phase. **Command:** `CI=true go test ./...`"
    -   Execute the announced command.
    -   If tests fail, you **must** inform the user and begin debugging. You may attempt to propose a fix a **maximum of two times**. If the tests still fail after your second proposed fix, you **must stop**, report the persistent failure, and ask the user for guidance.

4.  **Propose a Detailed, Actionable Manual Verification Plan:**
    -   **CRITICAL:** To generate the plan, first analyze `product.md`, `product-guidelines.md`, and `plan.md` to determine the user-facing goals of the completed phase.
    -   You **must** generate a step-by-step plan that walks the user through the verification process, including any necessary commands and specific, expected outcomes.
    -   The plan you present to the user **must** follow this format:

        **For a CI Job/Pipeline Config Change:**
        ```
        The automated checks have passed. For manual verification, please follow these steps:

        **Manual Verification Steps:**
        1.  **Validate the syntax by running:** `.ci/verify-jenkins-pipelines.sh`
        2.  **Trigger the affected job in staging** (or replay the Jenkins job for the target branch).
        3.  **Confirm that the job completes as expected** on prow.tidb.net/jenkins and, if it is a presubmit, that it reports the correct status on the PR.
        ```

        **For a Tooling Code Change (Go/Deno/Groovy):**
        ```
        The automated tests have passed. For manual verification, please follow these steps:

        **Manual Verification Steps:**
        1.  **Run the tool's test suite:** `cd tools/<tool> && go test ./...` (or `deno task test`).
        2.  **Execute the tool against a sample input** to confirm the expected output.
        3.  **Confirm that the result matches** the acceptance criteria in `plan.md`.
        ```

5.  **Await Explicit User Feedback:**
    -   After presenting the detailed plan, ask the user for confirmation: "**Does this meet your expectations? Please confirm with yes or provide feedback on what needs to be changed.**"
    -   **PAUSE** and await the user's response. Do not proceed without an explicit yes or confirmation.

6.  **Create Checkpoint Commit:**
    -   Stage all changes. If no changes occurred in this step, proceed with an empty commit.
    -   Perform the commit with a clear and concise message (e.g., `conductor(checkpoint): Checkpoint end of Phase X`).

7.  **Attach Auditable Verification Report using Git Notes:**
    -   **Step 7.1: Draft Note Content:** Create a detailed verification report including the automated test command, the manual verification steps, and the user's confirmation.
    -   **Step 7.2: Attach Note:** Use the `git notes` command and the full commit hash from the previous step to attach the full report to the checkpoint commit.

8.  **Get and Record Phase Checkpoint SHA:**
    -   **Step 8.1: Get Commit Hash:** Obtain the hash of the *just-created checkpoint commit* (`git log -1 --format="%H"`).
    -   **Step 8.2: Update Plan:** Read `plan.md`, find the heading for the completed phase, and append the first 7 characters of the commit hash in the format `[checkpoint: <sha>]`.
    -   **Step 8.3: Write Plan:** Write the updated content back to `plan.md`.

9. **Commit Plan Update:**
    - **Action:** Stage the modified `plan.md` file.
    - **Action:** Commit this change with a descriptive message following the format `conductor(plan): Mark phase '<PHASE NAME>' as complete`.

10.  **Announce Completion:** Inform the user that the phase is complete and the checkpoint has been created, with the detailed verification report attached as a git note.

### Quality Gates

Before marking any task complete, verify:

- [ ] All tests/validation scripts pass
- [ ] Code coverage meets requirements (>80%) for changed code modules
- [ ] Code follows project's code style guidelines (as defined in `code_styleguides/`)
- [ ] All public functions/methods are documented (e.g., GoDoc, JSDoc)
- [ ] Type safety is enforced (e.g., Go types, TypeScript types)
- [ ] No linting or static analysis errors (using the project's configured tools)
- [ ] Config changes validated by the repo verification scripts (`.ci/verify-jenkins-pipelines.sh`, prow/Tekton kustomization updates)
- [ ] Documentation updated if needed
- [ ] No security vulnerabilities introduced (no secrets committed; gitleaks clean)

## Development Commands

### Setup
```bash
# No global build step; each area is self-contained.
# Go tools have their own module (run per tool):
cd tools/<tool> && go mod tidy
# Groovy shared-library tests need Groovy installed (macOS): brew install groovy
```

### Daily Development
```bash
# Go tools
cd tools/<tool> && go build && go test ./...
# Deno/TypeScript tooling (scripts/, tools/reporters/...)
deno test               # non-watch; the deno.json "test" task runs `--watch` for local dev
deno check <script>.ts
# Groovy shared-library functions
groovy libraries/tipipeline/tests/TestComponent.groovy
groovy libraries/tisys/tests/TestMatrixCache.groovy
# Jenkins pipeline syntax verification
.ci/verify-jenkins-pipelines.sh
```

### Before Committing
```bash
pre-commit run --all-files                # eof-fixer + trailing-whitespace + gitleaks
# After editing prow-jobs or tekton YAML, regenerate kustomizations:
.ci/update-prow-job-kustomization.sh
.ci/update-tekton-kustomizations.sh
# After editing Jenkins pipelines:
.ci/verify-jenkins-pipelines.sh
```

## Testing Requirements

### Unit Testing
- Every code module must have corresponding tests: Go tools (`tools/*`), Deno/TypeScript tooling, and Groovy shared-library functions.
- Follow the naming/style used by existing tests in the same area (e.g., `libraries/tipipeline/tests/`, table-driven Groovy tests).
- Mock external dependencies (GitHub API, Jenkins, network).
- Test both success and failure cases.

### Integration / Config Validation
- Jenkins pipeline syntax is validated by `.ci/verify-jenkins-pipelines.sh`.
- Prow/Tekton YAML is validated by regenerating kustomizations (`.ci/update-prow-job-kustomization.sh`, `.ci/update-tekton-kustomizations.sh`) and reviewing rendered manifests.
- Job/pipeline behavior is verified by triggering or replaying the affected job in staging before promotion (see `docs/contributing.md`).
- Shared-library functions that call Jenkins APIs are exercised via the library test harness.

### Not Applicable
This project has no user-facing UI, so mobile/browser testing does not apply.

## Code Review Process

### Self-Review Checklist
Before requesting review:

1. **Functionality**
   - Change does what the track's `plan.md` specifies
   - Edge cases handled (branch/org variations, missing params)
   - Failure output is actionable for the developer who triggered the job

2. **Code Quality**
   - Follows style guide
   - DRY principle applied
   - Clear variable/function names
   - Appropriate comments

3. **Testing**
   - Unit tests comprehensive (code modules)
   - Config validated with repo verification scripts
   - Coverage adequate (>80%) for changed code modules

4. **Security**
   - No hardcoded secrets (gitleaks clean; credentials from Prow/Jenkins)
   - No sensitive values logged or exposed in build output
   - No unsafe execution of untrusted input in pipeline scripts

5. **Performance**
   - Pipeline timeouts, retries and resource requests are deliberate
   - No redundant stages or unnecessary waits

6. **Config Integrity**
   - Naming follows `<org>/<repo>/<branch>` and job-type conventions
   - All three layers are consistent: Prow trigger, Jenkins Job DSL, pipeline script
   - Generated manifests (kustomization) are in sync

## Commit Guidelines

### Message Format
```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

### Types
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `style`: Formatting, missing semicolons, etc.
- `refactor`: Code change that neither fixes a bug nor adds a feature
- `test`: Adding missing tests
- `chore`: Maintenance tasks

**Note:** In this repository, use a project-area scope (e.g. `prow-jobs`, `jobs`, `pipelines`, `tekton`, `libraries`, `tools`, `scripts`, `docs`, `conductor`). Reserve the `ci` type for `.github`/`.ci` changes only (the repo itself manages CI, so `ci(type)` is not used for job/pipeline edits).

### Examples
```bash
git commit -m "fix(pipelines): increase pipeline timeout"
git commit -m "chore(prow-jobs): bump image tags for latest jobs"
git commit -m "docs(conductor): adapt workflow for CI repo"
git commit -m "test(libraries): add unit tests for parseCIParamsFromPRTitle"
git commit -m "style(jobs): reformat job DSL file"
```

## Definition of Done

A task is complete when:

1. Change implemented to specification (`plan.md`)
2. Unit tests written and passing (for code modules)
3. Code coverage meets project requirements (>80% for changed code modules)
4. Config changes validated by the repo verification scripts
5. Code passes all configured linting and static analysis checks (pre-commit)
6. Documentation updated if needed
7. Implementation notes added to `plan.md`
8. Changes committed with proper message
9. Git note with task summary attached to the phase checkpoint commit

## Emergency Procedures

### Broken Production Job / Pipeline Config
1. Open a revert or hotfix PR targeting `main`
2. If the change touches a code module, write a failing test that reproduces the issue
3. Apply the minimal fix to the job/pipeline config
4. Verify by replaying/triggering the affected job in staging
5. Promote to production and monitor the affected jobs
6. Document in `plan.md`

### Data Loss
1. Stop all write operations
2. Restore from latest backup
3. Verify data integrity
4. Document incident
5. Update backup procedures

### Security Breach
1. Rotate all secrets immediately
2. Review access logs
3. Patch vulnerability
4. Notify affected users (if any)
5. Document and update security procedures

## Promotion Workflow

### Pre-Promotion Checklist
- [ ] All tests / validation scripts pass
- [ ] Coverage >80% for changed code modules
- [ ] No linting errors (pre-commit clean)
- [ ] Config change validated in staging (job triggered/replayed)
- [ ] OWNERS / SIG approval obtained
- [ ] Environment variables / credentials referenced correctly
- [ ] Generated manifests regenerated and in sync

### Promotion Steps
1. Merge feature branch to main
2. Verify generated manifests are committed and in sync
3. Promote the change from staging to production per `docs/contributing.md`
4. Monitor triggered jobs on prow.tidb.net / jenkins
5. Test critical paths (presubmits on affected repos/branches)
6. Watch for regressions

### Post-Promotion
1. Monitor job health and green rate
2. Check error logs on affected jobs
3. Gather feedback from CI owners
4. Plan next iteration

## Continuous Improvement

- Review workflow weekly
- Update based on pain points
- Document lessons learned
- Optimize for user happiness
- Keep things simple and maintainable
