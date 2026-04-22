# Contributing

> **Warm Tip:** Before starting, read through the existing documentation and check open issues or discussions. This helps avoid duplicate work and ensures your contribution aligns with project goals.

## Workflow for Pipeline Development

1. Identify the pipeline you need to modify
2. Copy it to the staging directory with your changes
3. Create a PR for review
4. Test in staging after the PR is merged
5. Create a new PR to move from staging to production
6. Include test results and links in your PR

## Recommended Checks Before Opening a PR

### PR Title Self-Check

Use the local PR title validator before opening or updating a pull request:

```bash
.ci/check-pr-title.sh --title "ci(prow): add PR title self-check"
```

The same validator powers the `pull-verify-pr-title` presubmit, so a PR with an invalid title will fail that check in Prow.

If your changes include `pipelines/**/*.groovy`, run these checks locally first:

1. Static validation:
   - `JENKINS_URL=https://do.pingcap.net/jenkins .ci/verify-jenkins-pipelines.sh`
2. Real replay validation:
   - `JENKINS_USER=<user> JENKINS_TOKEN=<token> .ci/replay-jenkins-build.sh --auto-changed --jenkins-url https://do.pingcap.net/jenkins --verbose`

For full command examples, behavior details (including `404` historical-build skip), and PR trigger workflow, see:
- `docs/guides/CI.md` -> `Pre-PR Verification for Jenkins Pipeline Changes`
