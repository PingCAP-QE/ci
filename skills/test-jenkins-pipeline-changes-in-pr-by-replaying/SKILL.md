---
name: test-jenkins-pipeline-changes-in-pr-by-replaying
description: "Test Jenkins pipeline changes in a PR by replaying changed jobs (latest-first) in PingCAP-QE/ci, tracking triggered run status, retrying failures up to a limit, and updating PR comments in task-item style. Use when asked to run .ci/replay-jenkins-build.sh, report replay URLs/statuses, or follow failures until resolved or max retries reached."
---

# Test Jenkins Pipeline Changes In PR By Replaying

## Overview
Replay pipeline jobs from PR scope by default (focus `latest` first), capture triggered Jenkins runs, monitor outcomes, and keep a PR comment updated with task-style checkboxes.

## Required Inputs
- Ask for `JENKINS_USER` and `JENKINS_TOKEN` before replay.
- Resolve PR number from current branch when not provided:

```bash
gh pr view --json number,url,headRefName
```

## Workflow
1. Resolve replay scope (default PR diff, or user-specified scope).
2. Replay only `latest` jobs first.
3. Update PR comment with task-item checklist and run URLs.
4. Poll run status; check only successful items.
5. For failures, analyze root cause and try to fix it in changed scope first.
6. Replay failed jobs again to verify the fix; continue until success or attempts exceed 3.
7. Mark unresolved failures with root-cause notes.

## Step 1: Resolve replay scope
Default behavior: auto-detect from current PR diff and keep only `latest` groovy pipelines:

```bash
git diff --name-only origin/main...HEAD | grep '^pipelines/.*/latest/.*\.groovy$'
```

If the user gives an explicit scope, use that instead of auto scope. Common explicit scopes:
- specific files (`--script-file <file>` one by one),
- branch range (`--base-sha <sha> --head-sha <sha>` with `--auto-changed`),
- path filter (for example only `pipelines/.../latest/...`).

Rule:
- No explicit scope from user -> use PR auto-detected scope.
- Explicit scope provided -> replay only the specified scope.

If `.ci/replay-jenkins-build.sh` fails on missing `rg`, create a temporary shim:

```bash
cat >/tmp/rg <<'SH'
#!/usr/bin/env bash
exec grep "$@"
SH
chmod +x /tmp/rg
```

Run replay commands with `PATH="/tmp:$PATH"` for that session.

## Step 2: Replay jobs
Replay each changed latest script with `.ci/replay-jenkins-build.sh`.

```bash
JENKINS_USER='<user>' JENKINS_TOKEN='<token>' \
.ci/replay-jenkins-build.sh \
  --script-file <pipeline.groovy> \
  --jenkins-url https://do.pingcap.net/jenkins \
  --selector lastSuccessfulBuild \
  --verbose
```

Notes:
- The replay script now inlines pod template YAML by default, so replay uses local pod template changes even when historical builds reference old SCM content.
- Use `--no-inline-pod-yaml` only when explicitly needed.

## Step 3: Update PR comment (task-item style)
Post or patch a PR comment with:
- One checkbox item per changed latest pipeline.
- Replay URL under each submitted item.
- Unsubmitted items marked unchecked with reason (for example, no `lastSuccessfulBuild`).

Prefer patching an existing replay comment via `gh api` when updating status in-place.

## Step 4: Track status and mark successes
Query each replay build URL:

```bash
curl -fsS -u '<user>:<token>' '<build_url>/api/json?tree=building,result,url'
```

Checklist policy:
- `[x]` only when `result=SUCCESS`.
- `[ ]` for `FAILURE`, `ABORTED`, `UNSTABLE`, `BUILDING`, or skipped.

## Step 5: Retry failures
- success, or
- the maximum number of retries (3) has been reached.
- attempts exceed 3 (initial + 3 retries max), whichever comes first.

Before each retry:
- inspect failure logs and classify infra/config/test failure,
- attempt a targeted fix (prefer pod template YAML/config fix over unrelated groovy workaround),
- replay again to confirm the fix effect.

Example retry:

```bash
JENKINS_USER='<user>' JENKINS_TOKEN='<token>' \
.ci/replay-jenkins-build.sh \
  --script-file <failed-pipeline.groovy> \
  --jenkins-url https://do.pingcap.net/jenkins \
  --selector lastSuccessfulBuild \
  --wait --verbose
```

Record each attempt URL and result in the PR comment.

## Failure triage hints
- `container utils not found in pod`: replay/pod-template mismatch symptom; ensure replay uses local pod YAML (default inline mode in replay script).
- Deterministic test assertion mismatch (for example SQL/result mismatch) after infra issue is solved: treat as test/runtime failure, likely unrelated to this CI config change; report explicitly.

## Guardrails
- Prefer fixing pod template YAML for missing containers; avoid pipeline-groovy workaround when the issue is template drift.
- Keep PR updates concise and reproducible: include exact Jenkins URLs, attempt counts, and final summary counts (`success`, `failure`, `skipped`).
