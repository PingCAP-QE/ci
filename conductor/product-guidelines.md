# Product Guidelines

These guidelines govern how CI/CD configuration is written, structured, documented and communicated in this repository. They complement the code style guides and are not language-specific.

## Prose & Documentation Style

- English is the canonical language for all documentation, commit messages, PR titles and inline comments.
- Write imperatively and concisely. Use active voice (e.g., "run the verification script" not "the verification script should be run").
- Every major area (prow-jobs, jobs, pipelines, tekton, libraries, tools) must remain discoverable: reference the relevant docs/guide when adding a new concept.
- When documenting a job, explain intent (what problem the job solves and when it runs), not just mechanics.
- Avoid `#NNN`-style bare numbers in PR text unless they are genuine GitHub references; numbers usually refer to Jenkins builds and must be wrapped in backticks or written as full URLs.

## Structure & Naming Conventions

- Paths follow `<org>/<repo>/<branch>`; keep branch-scoped config (latest, release-X.Y, hotfix) colocated with the jobs they configure.
- Prow YAML names: `<branch>-<jobtype>.yaml` where jobtype is presubmits/postsubmits/periodics.
- Jenkins Job DSL files: `[a-z][a-z0-9_]*[a-z0-9].groovy`.
- A job change is not complete until all three layers (Prow trigger, Jenkins DSL, pipeline) are consistent and verified.

## User Experience & Reviewability

- Optimize for reviewers: small, focused diffs; one logical change per PR.
- Optimize for downstream developers: CI names and failure output must make the cause actionable.
- Default to explicit over implicit in YAML/Groovy so behavior is self-evident to reviewers and future maintainers.

## Reliability & Safety Guidelines

- Never change production jobs directly: verify in staging first, then promote via a dedicated PR.
- After editing Prow/Tekton kustomization-referenced YAML, run the corresponding update/verify script so generated manifests stay in sync.
- Timeouts, retries and resource requests should be tuned deliberately, not left at arbitrary defaults.
- Changes that could regress trunk or release branches must carry test evidence in the PR description.

## Communication & Contribution Guidelines

- Conventional Commits for messages/PR titles: type(scope): subject, e.g. `ci(prow): add presubmit for tiflow lint`. Do not use the `ci:` type except for .github/.ci-only changes.
- Acknowledge approval requirements: `sig-approvers-ee` for CI infrastructure, project SIGs for their job configs.
- Deprecations are as important as additions: mark legacy tooling under tools/deprecated explicitly and schedule EOL cleanup.

## Inclusivity & Accessibility (relevant surfaces)

- Keep human-facing output (PR descriptions, failure summaries, docs) readable by non-native speakers: plain English, short sentences, no unexplained jargon.
- No content is generated for end-user UI, so accessibility applies to logs and notifications: prefer clear headings and concise summaries over walls of text.
