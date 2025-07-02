# Frequently Asked Questions (FAQ)

This document provides answers to common questions about our CI/CD infrastructure, development processes, and community guidelines.

## Table of Contents
- [CI Pipeline](#ci-pipeline)
- [Prow Bot](#prow-bot)
- [CLA/DCO](#cladco)

## CI Pipeline

### General Questions

#### Q1: Why is my PR's ci context display "Waiting for status to be reported"?
The PR's ci context display "Waiting for status to be reported" means that the PR is waiting for the ci to report the status. Please check if the PR has label `needs-ok-to-test`, if exists, please try to ask reviewer of the ORG to comment `ok-to-test` to trigger a new CI build. if the PR has no label `needs-ok-to-test`, but ci context still display "Waiting for status to be reported", please ask it in [Ask EE] group.

#### Q2: My tidb's PR need modify some mysql test cases, what is the best practice?
Follow these steps:
1. Prepare your tidb's PR. then prepare a tidb-test PR to modify the mysql test cases.
2. Please comment '/hold' on your tidb-test's PR.
3. In your tidb's PR, please specify the tidb-test's PR number in PR description. For example: `staistics: fix load stats from old version json | tidb-test=pr/2114`
4. At this time, triggering the mysql-test pipeline in your tidb PR will use the test code from the specified tidb-test PR for testing.
5. After your tidb's PR is merged, your tidb-test's PR will be merged automatically by the ti-chi-bot.

#### Q3: If I want to specify the tidb-test's PR in tidb's PR description, what should I notice?
Please ensure the base branch of your tidb-test's PR is the same as your tidb's PR. For example, if your tidb's PR is targetting `master`, your tidb-test's PR should targetting `master`. Only the base branch is same, the tidb-test's PR will be merged automatically after your tidb's PR is merged.

#### Q4: How can I check the status of my PR's ci pipeline?
You can check the status of your PR's ci pipeline by clicking the "Details" link in the PR's ci context. For the new github PR merge experience UI, just click the ci checks context, you can see the link url of the ci check.

#### Q5: All my PR's CI checks have passed, but my PR not merged yet, why?
For tidb & tiflow & pd these repos have already supported batch merging, and there may be multiple PRs merged together. In this case, please wait patiently. You can check the progress of the merge queue through the prow tide dashboard https://prow.tidb.net/tide

#### Q6: How to handle 404 errors when downloading bazel dependencies in TiDB PR's build pipeline?
Please upload the bazel dependencies in a internal machine.

## Prow Bot

### General Questions

#### Q1: What is the `tiprow` bot?
tiprow is the optional self-test bot acted on pingcap/tidb repo, it's maintained by [hawkingrei](https://github.com/hawkingrei). It will not affect the merging for pull request(CI checks created by tiprow will not affect the merging).

#### Q2: How to close or open a PR or Issue?
Please comment `/close` or `/reopen` in the PR or Issue.

#### Q3: For PRs submitted by external contributors, if this PR can automatically trigger CI?
For PRs submitted by external contributors, the CI will not be triggered automatically. Please ask the reviewer to comment `/ok-to-test` to trigger the CI.

#### Q4: How to hold or unhold the merge of the pull request for some reason?
Please comment `/hold` or `/unhold` in the PR.

#### Q5: How to find available commands from Prow bot in repo?
Please visit https://prow.tidb.net/command-help to find available commands.

#### Q6: How to solve conflicts or push some new changes in the cherry-pick PR?
Please chat with bot EE ChatOps in Lark IM(FeiShu). For example: `@EE ChatOps /cherry-pick-invite https://github.com/pingcap/tiflow/pull/10813  your_github_id`

#### Q7: Why my PR's ci context tide always show "Merge is forbidden"?
The "Merge is forbidden" tide status is expected behavior for Hotfix branches. Once all CI checks pass, the bot will proceed with merging the PR automatically.

#### Q8: Two people have already given LGTM on my PR, why doesn't my PR have the approved label yet?
lgtm label is counted through voting; if two people give an LGTM, the PR will have the LGTM label. However, approval is determined based on the scope of changes in the modified files of the PR. If a PR modifies code across multiple modules, it will require approvals from all OWNERS of those changed modules before receiving the approved label.

## CLA/DCO

### General Questions

#### Q1: How to solve the DCO "Expected — Waiting for status to be reported" problem?
1. In order to trigger a recheck on an existing pull request you may need to push a new commit.
2. If you have already pushed a new commit, you can try close and reopen the pull request.

#### Q2: How to resolve `licence/cla — Waiting for status to be reported` problem?
1. Check the url or the CLA status. It uses a public CLA service when the url is not "cla.pingcap.net"
2. Open https://cla.pingcap.net/, "recheck" the PR of your repository by pushing the "Recheck PRs" button.
3. If you can't find the repo which needs to `Recheck PRS`, Please try to reopen the PR to resolve it.

---

*Note: This FAQ is a living document and will be updated regularly*
