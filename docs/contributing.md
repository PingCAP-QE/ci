# Contributing

> **Warm Tip:** Before starting, read through the existing documentation and check open issues or discussions. This helps avoid duplicate work and ensures your contribution aligns with project goals.

## Workflow for Pipeline Development

1. Identify the pipeline you need to modify
2. Copy it to the staging directory with your changes
3. Create a PR for review
4. Test in staging after the PR is merged
5. Create a new PR to move from staging to production
6. Include test results and links in your PR

For non-trivial changes, track the work through the governance lifecycle (propose →
plan → implement → verify → promote) and use the review checklist in
[Job and Pipeline Change Governance](./guides/job-change-governance.md).

## Recommended Checks Before Opening a PR

If your changes include pipeline files under `jenkins/jobs/**` (`Jenkinsfile`), run these checks locally first:

1. Static validation:
   - `JENKINS_URL=https://prow.tidb.net/jenkins .ci/verify-jenkins-pipelines.sh`
2. Real replay validation:
   - `JENKINS_USER=<user> JENKINS_TOKEN=<token> .ci/replay-jenkins-build.sh --auto-changed --jenkins-url https://prow.tidb.net/jenkins --verbose`

For full command examples, behavior details (including `404` historical-build skip), and PR trigger workflow, see:
- `docs/guides/CI.md` -> `Pre-PR Verification for Jenkins Pipeline Changes`
